package com.pilot.server.session.notes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.pilot.core.session.SessionService;
import com.pilot.server.store.JdbcSupport;
import com.pilot.server.store.SqliteStore;

/**
 * 当前会话的「想法」：用户执行任务时随手记下的短内容，只保存到自己写的列表里。
 *
 * <p>保存想法不会写入事件、发送消息或排队；把它带回输入框由界面负责，
 * 这里只负责按会话持久化。SQLite 模式下刷新、重启后仍在；没有数据库时退回进程内存储（测试用）。
 */
@Service
public class SessionNotes {
    /** 一条想法的内容上限；超过就无法一眼扫完，也不适合再带回输入框。 */
    static final int MAX_CONTENT = 4000;
    /** 每个会话保留的想法条数上限，防止长期积累把列表撑爆。 */
    static final int MAX_NOTES = 200;

    /** 想法引用的来源消息；来源被历史编辑或清理后 available=false，但引用原文仍保留。 */
    public record Source(Integer sequence, String excerpt, String role, boolean available) {
    }

    public record Note(String id, String content, Instant createdAt, Instant updatedAt, Source source, String status,
        Link link) {
        public Note(String id, String content, Instant createdAt, Instant updatedAt) {
            this(id, content, createdAt, updatedAt, null, INBOX, null);
        }

        public Note(String id, String content, Instant createdAt, Instant updatedAt, Source source) {
            this(id, content, createdAt, updatedAt, source, INBOX, null);
        }

        public Note {
            status = status == null || status.isBlank() ? INBOX : status;
        }
    }

    /**
     * 想法记的是「当时想到了什么」，状态记的是「后来安排到哪了」。
     *
     * <p>四种状态刻意做得很少：想法不是看板，状态只用来把「还没安排的」挑出来。
     */
    public static final String INBOX = "inbox";
    public static final String PLANNED = "planned";
    public static final String DONE = "done";
    public static final String DROPPED = "dropped";
    private static final Set<String> STATUSES = Set.of(INBOX, PLANNED, DONE, DROPPED);

    /**
     * 想法转为任务后指向的目标。
     *
     * @param kind queue（已加入执行队列）或 session（已作为新任务提交）
     * @param id   会话 ID；队列条目本身是位置式的，会被重排与编辑，不记录位置以免立刻失效
     */
    public record Link(String kind, String id) {
    }

    /**
     * 转为任务的结果：给前端一段可直接提交的正文，加上更新后的想法。
     *
     * <p>后端不代发消息：想法与执行是两件事，只有用户主动提交才进入队列或新任务。
     */
    public record Promotion(String text, Note note) {
    }

    /** 创建时提交的来源：消息序号、选中原文与角色（user / assistant）。 */
    public record SourceInput(Integer sequence, String excerpt, String role) {
    }

    /** 项目内搜索时用到的会话引用；标题用于结果展示。 */
    public record SessionRef(String id, String title) {
    }

    public record NoteHit(String sessionId, String sessionTitle, String noteId, String content, String snippet,
        Instant createdAt, Source source) {
    }

    static final int SEARCH_LIMIT = 50;

    private final SqliteStore store;
    private final SessionService sessions;
    private final Map<String, LinkedHashMap<String, Note>> memory = new HashMap<>();
    private final Map<String, SourceInput> sources = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SessionNotes(ObjectProvider<SqliteStore> stores, ObjectProvider<SessionService> services) {
        this(stores.getIfAvailable(), services.getIfAvailable());
    }

    public SessionNotes(SqliteStore store) {
        this(store, null);
    }

    public SessionNotes(SqliteStore store, SessionService sessions) {
        this.store = store;
        this.sessions = sessions;
    }

    /** 新想法排在最前；同一会话内按写入顺序倒序，刷新后位置不变。 */
    public synchronized List<Note> list(String session) {
        if (store == null) {
            LinkedHashMap<String, Note> notes = memory.get(session);
            if (notes == null || notes.isEmpty()) {
                return List.of();
            }
            List<Note> result = new ArrayList<>(notes.values());
            Collections.reverse(result);
            return result.stream().map(note -> sources.containsKey(note.id()) ? new Note(note.id(), note.content(),
                note.createdAt(), note.updatedAt(), sourceOf(note.id(), session), note.status(), note.link()) : note)
                .toList();
        }
        return JdbcSupport.query(store,
            "SELECT n.id,n.content,n.created_at,n.updated_at,n.status,n.link_kind,n.link_id,"
                + "s.sequence,s.excerpt,s.role "
                + "FROM session_notes n LEFT JOIN session_note_sources s ON s.note_id=n.id "
                + "WHERE n.session_id=? ORDER BY n.seq DESC",
            query -> query.setString(1, session),
            rows -> new Note(rows.getString("id"), rows.getString("content"),
                Instant.parse(rows.getString("created_at")), Instant.parse(rows.getString("updated_at")),
                rows.getString("excerpt") == null ? null
                    : new Source((Integer)rows.getObject("sequence"), rows.getString("excerpt"), rows.getString("role"),
                        eventExists(session, (Integer)rows.getObject("sequence"))),
                rows.getString("status"), rows.getString("link_kind") == null ? null
                    : new Link(rows.getString("link_kind"), rows.getString("link_id"))),
            "读取想法失败");
    }

    /** 在指定的会话集合里按关键词匹配想法正文；结果按会话内新到旧，最多 {@value #SEARCH_LIMIT} 条。 */
    public synchronized List<NoteHit> search(List<SessionRef> sessionRefs, String query) {
        String term = query == null ? "" : query.strip();
        if (term.isEmpty() || sessionRefs == null || sessionRefs.isEmpty()) {
            return List.of();
        }
        String needle = term.toLowerCase(Locale.ROOT);
        List<NoteHit> hits = new ArrayList<>();
        for (SessionRef ref : sessionRefs) {
            for (Note note : list(ref.id())) {
                if (hits.size() >= SEARCH_LIMIT) {
                    return List.copyOf(hits);
                }
                int at = note.content().toLowerCase(Locale.ROOT).indexOf(needle);
                if (at < 0) {
                    continue;
                }
                int start = Math.max(0, at - 30);
                int end = Math.min(note.content().length(), at + term.length() + 30);
                String snippet = (start > 0 ? "…" : "") + note.content().substring(start, end)
                    + (end < note.content().length() ? "…" : "");
                hits.add(new NoteHit(ref.id(), ref.title(), note.id(), note.content(), snippet, note.createdAt(),
                    note.source()));
            }
        }
        return List.copyOf(hits);
    }

    public synchronized Note create(String session, String content) {
        return create(session, content, null);
    }

    public synchronized Note create(String session, String content, SourceInput source) {
        String text = validated(content);
        if (list(session).size() >= MAX_NOTES) {
            throw new IllegalArgumentException("每个对话最多 " + MAX_NOTES + " 条想法，请先删除一些");
        }
        Instant now = Instant.now();
        Note note = new Note("note_" + UUID.randomUUID().toString().replace("-", ""), text, now, now);
        if (store == null) {
            memory.computeIfAbsent(session, key -> new LinkedHashMap<>()).put(note.id(), note);
            if (source != null && source.sequence() != null) {
                sources.put(note.id(), source);
                return new Note(note.id(), note.content(), note.createdAt(), note.updatedAt(),
                    sourceOf(note.id(), session));
            }
            return note;
        }
        Note stored = note;
        long seq = JdbcSupport.queryOne(store, "SELECT COALESCE(MAX(seq),0)+1 FROM session_notes WHERE session_id=?",
            query -> query.setString(1, session), rows -> rows.getLong(1), "读取想法序号失败").orElse(1L);
        JdbcSupport.update(store,
            "INSERT INTO session_notes(id,session_id,seq,content,created_at,updated_at,status) VALUES(?,?,?,?,?,?,?)",
            query -> {
                query.setString(1, stored.id());
                query.setString(2, session);
                query.setLong(3, seq);
                query.setString(4, stored.content());
                query.setString(5, stored.createdAt().toString());
                query.setString(6, stored.updatedAt().toString());
                query.setString(7, stored.status());
            }, "保存想法失败");
        if (source != null && source.sequence() != null) {
            String excerpt = source.excerpt() == null ? "" : source.excerpt();
            JdbcSupport.update(store, "INSERT INTO session_note_sources(note_id,sequence,excerpt,role) VALUES(?,?,?,?)",
                query -> {
                    query.setString(1, stored.id());
                    query.setInt(2, source.sequence());
                    query.setString(3, excerpt);
                    query.setString(4, source.role());
                }, "保存想法来源失败");
            sources.put(stored.id(), source);
            return new Note(stored.id(), stored.content(), stored.createdAt(), stored.updatedAt(),
                sourceOf(stored.id(), session), stored.status(), stored.link());
        }
        return stored;
    }

    public synchronized Note update(String session, String noteId, String content) {
        String text = validated(content);
        Instant now = Instant.now();
        if (store == null) {
            LinkedHashMap<String, Note> notes = memory.get(session);
            Note old = notes == null ? null : notes.get(noteId);
            if (old == null) {
                throw new IllegalArgumentException("想法不存在或已删除");
            }
            Note next = new Note(old.id(), text, old.createdAt(), now, old.source(), old.status(), old.link());
            notes.put(noteId, next);
            return next;
        }
        // 允许编辑正文：状态与关联目标必须一起读回来，否则改一次字就把「已转任务」变回收件箱。
        Object[] state = JdbcSupport.queryOne(store,
            "SELECT created_at,status,link_kind,link_id FROM session_notes WHERE session_id=? AND id=?", query -> {
                query.setString(1, session);
                query.setString(2, noteId);
            }, rows -> new Object[] {Instant.parse(rows.getString(1)), rows.getString(2), rows.getString(3),
                rows.getString(4)},
            "读取想法失败").orElseThrow(() -> new IllegalArgumentException("想法不存在或已删除"));
        Instant created = (Instant)state[0];
        String status = (String)state[1];
        String linkKind = (String)state[2];
        String linkId = (String)state[3];
        int changed = JdbcSupport.update(store,
            "UPDATE session_notes SET content=?,updated_at=? WHERE session_id=? AND id=?", query -> {
                query.setString(1, text);
                query.setString(2, now.toString());
                query.setString(3, session);
                query.setString(4, noteId);
            }, "修改想法失败");
        if (changed == 0) {
            throw new IllegalArgumentException("想法不存在或已删除");
        }
        Source source = sources.containsKey(noteId) ? sourceOf(noteId, session)
            : JdbcSupport
                .queryOne(store, "SELECT sequence,excerpt,role FROM session_note_sources WHERE note_id=?",
                    query -> query.setString(1, noteId),
                    rows -> new Source((Integer)rows.getObject("sequence"), rows.getString("excerpt"),
                        rows.getString("role"), eventExists(session, (Integer)rows.getObject("sequence"))),
                    "读取想法来源失败")
                .orElse(null);
        return new Note(noteId, text, created, now, source, status,
            linkKind == null ? null : new Link(linkKind, linkId));
    }

    /**
     * 改状态：收件箱 / 已安排 / 已完成 / 已放弃。只改状态，不动正文。
     *
     * <p>状态是用户自己的安排，不是执行结果；因此这里不产生事件，也不推进队列。
     */
    public synchronized Note setStatus(String session, String noteId, String status, Link link) {
        String next = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(next)) {
            throw new IllegalArgumentException("未知想法状态：" + status + "（支持 inbox / planned / done / dropped）");
        }
        Note current = requireNote(session, noteId);
        Note updated = new Note(current.id(), current.content(), current.createdAt(), Instant.now(), current.source(),
            next, link == null ? current.link() : link);
        if (store == null) {
            memory.getOrDefault(session, new LinkedHashMap<>()).put(noteId, updated);
            return updated;
        }
        JdbcSupport.update(store,
            "UPDATE session_notes SET status=?,link_kind=?,link_id=?,updated_at=? WHERE session_id=? AND id=?",
            query -> {
                query.setString(1, updated.status());
                query.setString(2, updated.link() == null ? null : updated.link().kind());
                query.setString(3, updated.link() == null ? null : updated.link().id());
                query.setString(4, updated.updatedAt().toString());
                query.setString(5, session);
                query.setString(6, noteId);
            }, "修改想法状态失败");
        return updated;
    }

    /**
     * 把想法转成一段可直接提交的任务正文，并把状态标为已安排。
     *
     * <p>只组装文本，不代发消息：想法与执行是两件事，提交动作必须由用户触发。
     * 有来源引用时把原文一起带上，避免转成任务后丢失「当初为什么记这条」。
     */
    public synchronized Promotion promote(String session, String noteId, Link link) {
        Note note = requireNote(session, noteId);
        StringBuilder text = new StringBuilder(note.content().strip());
        Source source = note.source();
        if (source != null && source.excerpt() != null && !source.excerpt().isBlank()) {
            text.append("\n\n来自");
            if (source.role() != null && !source.role().isBlank()) {
                text.append(' ').append("assistant".equals(source.role()) ? "助手回复" : "我的提问");
            }
            if (source.sequence() != null) {
                text.append("（第 ").append(source.sequence()).append(" 条）");
            }
            text.append("：\n> ").append(source.excerpt().strip().replace("\n", "\n> "));
        }
        Note updated = setStatus(session, noteId, PLANNED, link);
        return new Promotion(text.toString(), updated);
    }

    private Note requireNote(String session, String noteId) {
        return list(session).stream().filter(note -> note.id().equals(noteId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("想法不存在或已删除"));
    }

    /** 删除是幂等的：重复删除同一想法不报错，避免多端重复提交互相打断。 */
    public synchronized void delete(String session, String noteId) {
        if (store == null) {
            LinkedHashMap<String, Note> notes = memory.get(session);
            if (notes != null) {
                notes.remove(noteId);
            }
            return;
        }
        JdbcSupport.update(store, "DELETE FROM session_note_sources WHERE note_id=?",
            query -> query.setString(1, noteId), "删除想法来源失败");
        JdbcSupport.update(store, "DELETE FROM session_notes WHERE session_id=? AND id=?", query -> {
            query.setString(1, session);
            query.setString(2, noteId);
        }, "删除想法失败");
    }

    /** 删除会话时一并清掉它的想法。 */
    public synchronized void deleteBySession(String session) {
        if (store == null) {
            if (memory.get(session) != null) {
                memory.get(session).keySet().forEach(sources::remove);
            }
            memory.remove(session);
            return;
        }
        JdbcSupport.update(store,
            "DELETE FROM session_note_sources WHERE note_id IN (SELECT id FROM session_notes WHERE session_id=?)",
            query -> query.setString(1, session), "清理想法来源失败");
        JdbcSupport.update(store, "DELETE FROM session_notes WHERE session_id=?", query -> query.setString(1, session),
            "清理想法失败");
    }

    private Source sourceOf(String noteId, String session) {
        SourceInput input = sources.get(noteId);
        if (input == null) {
            return null;
        }
        return new Source(input.sequence(), input.excerpt(), input.role(), eventExists(session, input.sequence()));
    }

    /** 历史编辑或清理后消息序号可能不存在：来源仍保留原文，只是标记为不可用。 */
    private boolean eventExists(String session, Integer sequence) {
        if (sequence == null || sessions == null) {
            return false;
        }
        return sessions.events(session).stream().anyMatch(event -> event.sequence() == sequence);
    }

    private static String validated(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("想法内容不能为空");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT) {
            throw new IllegalArgumentException("每条想法最多 " + MAX_CONTENT + " 个字符");
        }
        return content;
    }
}
