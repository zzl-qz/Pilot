package com.pilot.server.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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

    public record Note(String id, String content, Instant createdAt, Instant updatedAt) {
    }

    private final SqliteStore store;
    private final Map<String, LinkedHashMap<String, Note>> memory = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SessionNotes(ObjectProvider<SqliteStore> provider) {
        this(provider.getIfAvailable());
    }

    public SessionNotes(SqliteStore store) {
        this.store = store;
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
            return List.copyOf(result);
        }
        return JdbcSupport
            .query(store,
                "SELECT id,content,created_at,updated_at FROM session_notes WHERE session_id=? ORDER BY seq DESC",
                query -> query.setString(1, session),
                rows -> new Note(rows.getString("id"), rows.getString("content"),
                    Instant.parse(rows.getString("created_at")), Instant.parse(rows.getString("updated_at"))),
                "读取想法失败");
    }

    public synchronized Note create(String session, String content) {
        String text = validated(content);
        if (list(session).size() >= MAX_NOTES) {
            throw new IllegalArgumentException("每个对话最多 " + MAX_NOTES + " 条想法，请先删除一些");
        }
        Instant now = Instant.now();
        Note note = new Note("note_" + UUID.randomUUID().toString().replace("-", ""), text, now, now);
        if (store == null) {
            memory.computeIfAbsent(session, key -> new LinkedHashMap<>()).put(note.id(), note);
            return note;
        }
        long seq = JdbcSupport.queryOne(store, "SELECT COALESCE(MAX(seq),0)+1 FROM session_notes WHERE session_id=?",
            query -> query.setString(1, session), rows -> rows.getLong(1), "读取想法序号失败").orElse(1L);
        JdbcSupport.update(store,
            "INSERT INTO session_notes(id,session_id,seq,content,created_at,updated_at) VALUES(?,?,?,?,?,?)", query -> {
                query.setString(1, note.id());
                query.setString(2, session);
                query.setLong(3, seq);
                query.setString(4, note.content());
                query.setString(5, note.createdAt().toString());
                query.setString(6, note.updatedAt().toString());
            }, "保存想法失败");
        return note;
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
            Note next = new Note(old.id(), text, old.createdAt(), now);
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
        return new Note(noteId, text, created, now);
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
        JdbcSupport.update(store, "DELETE FROM session_notes WHERE session_id=? AND id=?", query -> {
            query.setString(1, session);
            query.setString(2, noteId);
        }, "删除想法失败");
    }

    /** 删除会话时一并清掉它的想法。 */
    public synchronized void deleteBySession(String session) {
        if (store == null) {
            memory.remove(session);
            return;
        }
        JdbcSupport.update(store, "DELETE FROM session_notes WHERE session_id=?", query -> query.setString(1, session),
            "清理想法失败");
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
