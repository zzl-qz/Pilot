package com.pilot.server.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Durable per-session queue for user steering messages. */
public final class QueuedMessageRepository {
    private final SqliteStore store;
    private final ConcurrentMap<String, List<String>> memory = new ConcurrentHashMap<>();
    private final Set<String> held = ConcurrentHashMap.newKeySet();

    public QueuedMessageRepository(SqliteStore store) {
        this.store = store;
    }

    public QueuedMessageRepository() {
        this.store = null;
    }

    public List<String> load(String sessionId) {
        if (store == null) {
            return memory.getOrDefault(sessionId, List.of());
        }
        return store.query("SELECT content FROM queued_messages WHERE session_id = ? ORDER BY position",
            statement -> statement.setString(1, sessionId), rows -> rows.getString(1), "读取排队消息失败");
    }

    public void save(String sessionId, List<String> messages) {
        saveAll(Map.of(sessionId, messages));
    }

    /** 队列是否被用户手动暂停；暂停只影响「自动开始下一条」，当前运行继续。 */
    public boolean held(String sessionId) {
        if (store == null) {
            return held.contains(sessionId);
        }
        return store.queryOne("SELECT held FROM queue_holds WHERE session_id = ?",
                statement -> statement.setString(1, sessionId), rows -> rows.getInt(1) != 0, "读取队列暂停状态失败")
            .orElse(false);
    }

    public void setHeld(String sessionId, boolean value) {
        if (store == null) {
            if (value) {
                held.add(sessionId);
            } else {
                held.remove(sessionId);
            }
            return;
        }
        if (value) {
            store.update(
                "INSERT INTO queue_holds(session_id, held) VALUES (?, 1) "
                    + "ON CONFLICT(session_id) DO UPDATE SET held = 1",
                statement -> statement.setString(1, sessionId), "保存队列暂停状态失败");
        } else {
            store.update("DELETE FROM queue_holds WHERE session_id = ?",
                statement -> statement.setString(1, sessionId), "清理队列暂停状态失败");
        }
    }

    /** 会话删除时清掉暂停状态（队列行本身由 save(sessionId, List.of()) 清）。 */
    public void clearHold(String sessionId) {
        setHeld(sessionId, false);
        held.remove(sessionId);
    }

    /** 一次事务移动普通队列与引导队列，失败时两个队列一起回滚。 */
    public synchronized void saveAll(Map<String, List<String>> queues) {
        if (store == null) {
            queues.forEach((id, values) -> memory.put(id, List.copyOf(values)));
            return;
        }
        store.inTransaction(connection -> {
            for (var entry : queues.entrySet()) {
                String sessionId = entry.getKey();
                List<String> messages = entry.getValue();
                List<SqliteStore.Binder> rows = new ArrayList<>();
                for (int position = 0; position < messages.size(); position++) {
                    String content = messages.get(position);
                    int index = position;
                    rows.add(statement -> {
                        statement.setString(1, sessionId);
                        statement.setInt(2, index);
                        statement.setString(3, content);
                    });
                }
                store.exec(connection, "DELETE FROM queued_messages WHERE session_id = ?",
                    statement -> statement.setString(1, sessionId));
                store.batch(connection,
                    "INSERT INTO queued_messages(session_id, position, content) VALUES (?, ?, ?)", rows);
            }
        }, "保存排队消息失败");
    }
}
