package com.pilot.server.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        if (store == null) return memory.getOrDefault(sessionId, List.of());
        try (Connection connection = store.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT content FROM queued_messages WHERE session_id = ? ORDER BY position")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (rows.next()) values.add(rows.getString(1));
                return List.copyOf(values);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取排队消息失败", e);
        }
    }

    public void save(String sessionId, List<String> messages) {
        if (store == null) {
            if (messages.isEmpty()) memory.remove(sessionId);
            else memory.put(sessionId, List.copyOf(messages));
            return;
        }
        try (Connection connection = store.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM queued_messages WHERE session_id = ?")) {
                delete.setString(1, sessionId);
                delete.executeUpdate();
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO queued_messages(session_id, position, content) VALUES (?, ?, ?)")) {
                    for (int i = 0; i < messages.size(); i++) {
                        insert.setString(1, sessionId);
                        insert.setInt(2, i);
                        insert.setString(3, messages.get(i));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存排队消息失败", e);
        }
    }
}
