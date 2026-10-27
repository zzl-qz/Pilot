package com.pilot.server.store;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.session.NoSuchSessionException;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite 版会话存储：会话与事件两张表，事件序号在事务内自增。 */
public final class JdbcSessionRepository implements SessionRepository {
    private static final int WRITE_ATTEMPTS = 3;

    private final SqliteStore store;
    private final EventCodec codec;

    public JdbcSessionRepository(SqliteStore store, EventCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @Override
    public Session create(String sessionId, String title, String workspace, Instant createdAt) {
        String sql = "INSERT INTO sessions (id, title, workspace, created_at, status, sequence) VALUES (?, ?, ?, ?, ?, 0)";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, title);
            statement.setString(3, workspace);
            statement.setString(4, createdAt.toString());
            statement.setString(5, SessionStatus.CREATED.name());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("创建会话失败: " + sessionId, e);
        }
        return new Session(sessionId, title, createdAt, SessionStatus.CREATED, workspace);
    }

    @Override
    public Optional<Session> find(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String sql = "SELECT id, title, workspace, created_at, status FROM sessions WHERE id = ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取会话失败: " + sessionId, e);
        }
    }

    @Override
    public List<Session> list() {
        String sql = "SELECT id, title, workspace, created_at, status FROM sessions ORDER BY created_at DESC";
        try (Connection connection = store.open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            var result = new ArrayList<Session>();
            while (rows.next()) {
                result.add(map(rows));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("读取会话列表失败", e);
        }
    }

    @Override
    public boolean rename(String sessionId, String title) {
        String sql = "UPDATE sessions SET title = ? WHERE id = ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, sessionId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("重命名会话失败: " + sessionId, e);
        }
    }

    @Override
    public boolean delete(String sessionId) {
        try (Connection connection = store.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement events = connection.prepareStatement("DELETE FROM events WHERE session_id = ?")) {
                    events.setString(1, sessionId);
                    events.executeUpdate();
                }
                boolean removed;
                try (PreparedStatement sessions = connection.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
                    sessions.setString(1, sessionId);
                    removed = sessions.executeUpdate() > 0;
                }
                connection.commit();
                return removed;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除会话失败: " + sessionId, e);
        }
    }

    @Override
    public List<EventEnvelope> events(String sessionId) {
        return query(sessionId, 0L);
    }

    @Override
    public List<EventEnvelope> eventsAfter(String sessionId, long afterSequence) {
        return query(sessionId, afterSequence);
    }

    @Override
    public long latestSequence(String sessionId) {
        String sql = "SELECT sequence FROM sessions WHERE id = ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取会话序号失败: " + sessionId, e);
        }
    }

    @Override
    public int dropEventsFrom(String sessionId, long fromSequence) {
        if (find(sessionId).isEmpty()) {
            throw new NoSuchSessionException(sessionId);
        }
        String sql = "DELETE FROM events WHERE session_id = ? AND sequence >= ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setLong(2, fromSequence);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除事件失败: " + sessionId, e);
        }
    }

    @Override
    public EventEnvelope append(String sessionId, String runId, Instant timestamp, SessionEvent event,
                                SessionStatus status) {
        SQLException last = null;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                return appendOnce(sessionId, runId, timestamp, event, status);
            } catch (SQLException e) {
                last = e;
                if (!isBusy(e)) {
                    break;
                }
                sleep(attempt * 30L);
            }
        }
        throw new IllegalStateException("写入事件失败: " + sessionId, last);
    }

    private EventEnvelope appendOnce(String sessionId, String runId, Instant timestamp, SessionEvent event,
                                     SessionStatus status) throws SQLException {
        try (Connection connection = store.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement bump = connection.prepareStatement(
                        "UPDATE sessions SET sequence = sequence + 1, status = ? WHERE id = ?")) {
                    bump.setString(1, status.name());
                    bump.setString(2, sessionId);
                    if (bump.executeUpdate() == 0) {
                        connection.rollback();
                        throw new NoSuchSessionException(sessionId);
                    }
                }

                long sequence;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT sequence FROM sessions WHERE id = ?")) {
                    read.setString(1, sessionId);
                    try (ResultSet rows = read.executeQuery()) {
                        rows.next();
                        sequence = rows.getLong(1);
                    }
                }

                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO events (session_id, sequence, run_id, type, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, sessionId);
                    insert.setLong(2, sequence);
                    insert.setString(3, runId);
                    insert.setString(4, event.type().wireName());
                    insert.setString(5, codec.encode(event));
                    insert.setString(6, timestamp.toString());
                    insert.executeUpdate();
                }

                connection.commit();
                return new EventEnvelope(sequence, timestamp, event.type().wireName(), sessionId, runId, event);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private List<EventEnvelope> query(String sessionId, long afterSequence) {
        if (find(sessionId).isEmpty()) {
            throw new NoSuchSessionException(sessionId);
        }
        String sql = "SELECT sequence, run_id, type, payload, created_at FROM events "
                + "WHERE session_id = ? AND sequence > ? ORDER BY sequence";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setLong(2, afterSequence);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new ArrayList<EventEnvelope>();
                while (rows.next()) {
                    long sequence = rows.getLong("sequence");
                    String runId = rows.getString("run_id");
                    String type = rows.getString("type");
                    String payload = rows.getString("payload");
                    Instant timestamp = Instant.parse(rows.getString("created_at"));
                    result.add(new EventEnvelope(sequence, timestamp, type, sessionId, runId,
                            codec.decode(type, payload)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取事件失败: " + sessionId, e);
        }
    }

    private static Session map(ResultSet rows) throws SQLException {
        return new Session(
                rows.getString("id"),
                rows.getString("title"),
                Instant.parse(rows.getString("created_at")),
                SessionStatus.valueOf(rows.getString("status")),
                rows.getString("workspace"));
    }

    private static boolean isBusy(SQLException e) {
        String message = e.getMessage();
        return message != null && (message.contains("SQLITE_BUSY") || message.contains("database is locked"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
