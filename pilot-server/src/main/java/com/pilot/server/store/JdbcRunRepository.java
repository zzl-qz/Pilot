package com.pilot.server.store;

import com.pilot.core.session.RunRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/** 运行记录落库，供状态校准与后续断点续跑使用。 */
public final class JdbcRunRepository implements RunRepository {
    private final SqliteStore store;

    public JdbcRunRepository(SqliteStore store) {
        this.store = store;
    }

    @Override
    public void started(String runId, String sessionId, Instant at) {
        String sql = "INSERT OR REPLACE INTO runs (id, session_id, status, started_at, finished_at) VALUES (?, ?, 'RUNNING', ?, NULL)";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, sessionId);
            statement.setString(3, at.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("记录运行开始失败: " + runId, e);
        }
    }

    @Override
    public void finished(String runId, String status, Instant at) {
        String sql = "UPDATE runs SET status = ?, finished_at = ? WHERE id = ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, at.toString());
            statement.setString(3, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("记录运行结束失败: " + runId, e);
        }
    }

    @Override
    public int markInterrupted(Instant at) {
        String sql = "UPDATE runs SET status = 'INTERRUPTED', finished_at = ? WHERE status = 'RUNNING'";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, at.toString());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("标记中断运行失败", e);
        }
    }
}
