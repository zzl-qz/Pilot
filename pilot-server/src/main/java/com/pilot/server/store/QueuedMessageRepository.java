package com.pilot.server.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Durable per-session queue for user steering messages. */
public final class QueuedMessageRepository {
    private final SqliteStore store;
    private final ConcurrentMap<String, List<String>> memory = new ConcurrentHashMap<>();

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
        return JdbcSupport.query(store,
                "SELECT content FROM queued_messages WHERE session_id = ? ORDER BY position",
                statement -> statement.setString(1, sessionId),
                rows -> rows.getString(1),
                "读取排队消息失败");
    }

    public void save(String sessionId, List<String> messages) {
        saveAll(java.util.Map.of(sessionId, messages));
    }

    /** 一次事务移动普通队列与引导队列，失败时两个队列一起回滚。 */
    public synchronized void saveAll(java.util.Map<String, List<String>> queues) {
        if (store == null) {
            queues.forEach((id, values) -> memory.put(id, List.copyOf(values)));
            return;
        }
        JdbcSupport.inTransaction(store, connection -> {
            for (var entry : queues.entrySet()) {
                String sessionId = entry.getKey();
                List<String> messages = entry.getValue();
                List<JdbcSupport.Binder> rows = new ArrayList<>();
                for (int position = 0; position < messages.size(); position++) {
                    String content = messages.get(position);
                    int index = position;
                    rows.add(statement -> {
                        statement.setString(1, sessionId);
                        statement.setInt(2, index);
                        statement.setString(3, content);
                    });
                }
                JdbcSupport.exec(connection, "DELETE FROM queued_messages WHERE session_id = ?",
                        statement -> statement.setString(1, sessionId));
                JdbcSupport.batch(connection,
                        "INSERT INTO queued_messages(session_id, position, content) VALUES (?, ?, ?)", rows);
            }
        }, "保存排队消息失败");
    }
}
