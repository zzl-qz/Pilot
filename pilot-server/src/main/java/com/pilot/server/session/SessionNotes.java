package com.pilot.server.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public record Note(String id, String content, Instant createdAt, Instant updatedAt, Source source) {
        public Note(String id, String content, Instant createdAt, Instant updatedAt) {
            this(id, content, createdAt, updatedAt, null);
        }
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
            return result.stream().map(note -> sources.containsKey(note.id())
                ? new Note(note.id(), note.content(), note.createdAt(), note.updatedAt(), sourceOf(note.id(), session))
                : note).toList();
        }
        return JdbcSupport
            .query(store,
                "SELECT n.id,n.content,n.created_at,n.updated_at,s.sequence,s.excerpt,s.role "
                    + "FROM session_notes n LEFT JOIN session_note_sources s ON s.note_id=n.id "
                    + "WHERE n.session_id=? ORDER BY n.seq DESC",
                query -> query.setString(1, session),
                rows -> new Note(rows.getString("id"), rows.getString("content"),
                    Instant.parse(rows.getString("created_at")), Instant.parse(rows.getString("updated_at")),
                    rows.getString("excerpt") == null ? null
                        : new Source((Integer)rows.getObject("sequence"), rows.getString("excerpt"),
                            rows.getString("role"), eventExists(session, (Integer)rows.getObject("sequence")))),
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
            "INSERT INTO session_notes(id,session_id,seq,content,created_at,updated_at) VALUES(?,?,?,?,?,?)", query -> {
                query.setString(1, stored.id());
                query.setString(2, session);
                query.setLong(3, seq);
                query.setString(4, stored.content());
                query.setString(5, stored.createdAt().toString());
                query.setString(6, stored.updatedAt().toString());
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
                sourceOf(stored.id(), session));
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
            Note next = new Note(old.id(), text, old.createdAt(), now, old.source());
            notes.put(noteId, next);
            return next;
        }
        Instant created =
            JdbcSupport.queryOne(store, "SELECT created_at FROM session_notes WHERE session_id=? AND id=?", query -> {
                query.setString(1, session);
                query.setString(2, noteId);
            }, rows -> Instant.parse(rows.getString(1)), "读取想法失败")
                .orElseThrow(() -> new IllegalArgumentException("想法不存在或已删除"));
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
        return new Note(noteId, text, created, now, source);
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
