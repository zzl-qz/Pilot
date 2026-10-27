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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** SQLite 版会话存储：会话与事件两张表，事件序号在事务内自增。 */
public final class JdbcSessionRepository implements SessionRepository {
    private static final int WRITE_ATTEMPTS = 3;
    private static final String COLUMNS =
            "id, title, workspace, created_at, status, project_id, is_blank, archived";

    private final SqliteStore store;
    private final EventCodec codec;

    public JdbcSessionRepository(SqliteStore store, EventCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @Override
    public Session create(String sessionId, String title, String workspace, Instant createdAt, String projectId,
                          boolean isBlank) {
        JdbcSupport.update(store,
                "INSERT INTO sessions (id, title, workspace, created_at, status, sequence, project_id, "
                        + "is_blank, archived) VALUES (?, ?, ?, ?, ?, 0, ?, ?, 0)",
                statement -> {
                    statement.setString(1, sessionId);
                    statement.setString(2, title);
                    statement.setString(3, workspace);
                    statement.setString(4, createdAt.toString());
                    statement.setString(5, SessionStatus.CREATED.name());
                    statement.setString(6, projectId);
                    statement.setInt(7, isBlank ? 1 : 0);
                },
                "创建会话失败: " + sessionId);
        return new Session(sessionId, title, createdAt, SessionStatus.CREATED, workspace, projectId, isBlank, false);
    }

    @Override
    public boolean markNotBlank(String sessionId) {
        return JdbcSupport.update(store, "UPDATE sessions SET is_blank = 0 WHERE id = ? AND is_blank = 1",
                statement -> statement.setString(1, sessionId),
                "清除空白标记失败: " + sessionId) > 0;
    }

    @Override
    public boolean setArchived(String sessionId, boolean archived) {
        return JdbcSupport.update(store, "UPDATE sessions SET archived = ? WHERE id = ?",
                statement -> {
                    statement.setInt(1, archived ? 1 : 0);
                    statement.setString(2, sessionId);
                },
                "归档会话失败: " + sessionId) > 0;
    }

    @Override
    public Optional<Session> find(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return JdbcSupport.queryOne(store, "SELECT " + COLUMNS + " FROM sessions WHERE id = ?",
                statement -> statement.setString(1, sessionId),
                JdbcSessionRepository::map,
                "读取会话失败: " + sessionId);
    }

    @Override
    public List<Session> list() {
        return JdbcSupport.query(store, "SELECT " + COLUMNS + " FROM sessions ORDER BY created_at DESC",
                statement -> { },
                JdbcSessionRepository::map,
                "读取会话列表失败");
    }

    @Override
    public List<Session> listByProject(String projectId) {
        if (projectId == null) {
            // 未分组的历史会话：project_id 为空
            return JdbcSupport.query(store,
                    "SELECT " + COLUMNS + " FROM sessions WHERE project_id IS NULL ORDER BY created_at DESC",
                    statement -> { },
                    JdbcSessionRepository::map,
                    "读取未分组会话失败");
        }
        return JdbcSupport.query(store,
                "SELECT " + COLUMNS + " FROM sessions WHERE project_id = ? ORDER BY created_at DESC",
                statement -> statement.setString(1, projectId),
                JdbcSessionRepository::map,
                "读取项目会话失败: " + projectId);
    }

    @Override
    public boolean updateWorkspace(String sessionId, String workspace) {
        return JdbcSupport.update(store, "UPDATE sessions SET workspace = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, workspace);
                    statement.setString(2, sessionId);
                },
                "切换会话工作区失败: " + sessionId) > 0;
    }

    @Override
    public boolean rename(String sessionId, String title) {
        return JdbcSupport.update(store, "UPDATE sessions SET title = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, title);
                    statement.setString(2, sessionId);
                },
                "重命名会话失败: " + sessionId) > 0;
    }

    @Override
    public boolean delete(String sessionId) {
        boolean[] removed = {false};
        JdbcSupport.inTransaction(store, connection -> {
            JdbcSupport.exec(connection, "DELETE FROM events WHERE session_id = ?",
                    statement -> statement.setString(1, sessionId));
            removed[0] = JdbcSupport.exec(connection, "DELETE FROM sessions WHERE id = ?",
                    statement -> statement.setString(1, sessionId)) > 0;
        }, "删除会话失败: " + sessionId);
        return removed[0];
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
    public long eventCount(String sessionId) {
        return JdbcSupport.query(store, "SELECT COUNT(*) FROM events WHERE session_id = ?",
                statement -> statement.setString(1, sessionId),
                rows -> rows.getLong(1),
                "统计事件失败: " + sessionId).stream().findFirst().orElse(0L);
    }

    /**
     * 倒序取一页再反转：SQLite 走 {@code (session_id, sequence)} 主键索引，
     * 只读需要的行，不必把整个会话的事件都读进内存再切片。
     */
    @Override
    public List<EventEnvelope> eventsPage(String sessionId, Long before, int limit) {
        String sql = "SELECT sequence, run_id, type, payload, created_at FROM events WHERE session_id = ?"
                + (before != null ? " AND sequence < ?" : "")
                + " ORDER BY sequence DESC LIMIT ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, sessionId);
            if (before != null) {
                statement.setLong(index++, before);
            }
            statement.setInt(index, Math.max(1, limit));
            var page = new ArrayList<EventEnvelope>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    page.add(mapEvent(rows, sessionId));
                }
            }
            Collections.reverse(page);
            return List.copyOf(page);
        } catch (SQLException e) {
            throw new IllegalStateException("读取事件分页失败: " + sessionId, e);
        }
    }

    @Override
    public long latestSequence(String sessionId) {
        return JdbcSupport.query(store, "SELECT sequence FROM sessions WHERE id = ?",
                statement -> statement.setString(1, sessionId),
                rows -> rows.getLong(1),
                "读取会话序号失败: " + sessionId).stream().findFirst().orElse(0L);
    }

    @Override
    public int dropEventsFrom(String sessionId, long fromSequence) {
        if (find(sessionId).isEmpty()) {
            throw new NoSuchSessionException(sessionId);
        }
        return JdbcSupport.update(store,
                "DELETE FROM events WHERE session_id = ? AND sequence >= ?",
                statement -> {
                    statement.setString(1, sessionId);
                    statement.setLong(2, fromSequence);
                },
                "删除事件失败: " + sessionId);
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
                // 一条 UPDATE ... RETURNING 同时完成「序号自增」和「读回新序号」，
                // 比 UPDATE + SELECT 少一次往返，也少一次 BUSY 窗口。
                long sequence;
                try (PreparedStatement bump = connection.prepareStatement(
                        "UPDATE sessions SET sequence = sequence + 1, status = ? WHERE id = ? RETURNING sequence")) {
                    bump.setString(1, status.name());
                    bump.setString(2, sessionId);
                    try (ResultSet rows = bump.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            throw new NoSuchSessionException(sessionId);
                        }
                        sequence = rows.getLong(1);
                    }
                }

                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO events (session_id, sequence, run_id, type, payload, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
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
        String sql = "SELECT sequence, run_id, type, payload, created_at FROM events "
                + "WHERE session_id = ? AND sequence > ? ORDER BY sequence";
        try (Connection connection = store.open()) {
            // 存在性检查和事件查询共用一个连接：分开查会多一次建连与一次往返
            try (PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM sessions WHERE id = ?")) {
                exists.setString(1, sessionId);
                try (ResultSet rows = exists.executeQuery()) {
                    if (!rows.next()) {
                        throw new NoSuchSessionException(sessionId);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, sessionId);
                statement.setLong(2, afterSequence);
                try (ResultSet rows = statement.executeQuery()) {
                    var result = new ArrayList<EventEnvelope>();
                    while (rows.next()) {
                        result.add(mapEvent(rows, sessionId));
                    }
                    return List.copyOf(result);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取事件失败: " + sessionId, e);
        }
    }

    private EventEnvelope mapEvent(ResultSet rows, String sessionId) throws SQLException {
        long sequence = rows.getLong("sequence");
        String runId = rows.getString("run_id");
        String type = rows.getString("type");
        String payload = rows.getString("payload");
        Instant timestamp = Instant.parse(rows.getString("created_at"));
        return new EventEnvelope(sequence, timestamp, type, sessionId, runId, codec.decode(type, payload));
    }

    private static Session map(ResultSet rows) throws SQLException {
        return new Session(
                rows.getString("id"),
                rows.getString("title"),
                Instant.parse(rows.getString("created_at")),
                SessionStatus.valueOf(rows.getString("status")),
                rows.getString("workspace"),
                rows.getString("project_id"),
                rows.getInt("is_blank") == 1,
                rows.getInt("archived") == 1);
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
