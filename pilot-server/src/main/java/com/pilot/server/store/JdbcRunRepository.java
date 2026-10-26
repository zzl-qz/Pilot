package com.pilot.server.store;

import com.pilot.core.session.InterruptedRun;
import com.pilot.core.session.RunRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    public List<InterruptedRun> markInterrupted(Instant at) {
        // 先查后改：只有拿到 runId/sessionId，调用方才能给对应会话补终态事件
        String select = "SELECT id, session_id FROM runs WHERE status = 'RUNNING'";
        String update = "UPDATE runs SET status = 'INTERRUPTED', finished_at = ? WHERE status = 'RUNNING'";
        try (Connection connection = store.open()) {
            List<InterruptedRun> interrupted = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(select);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    interrupted.add(new InterruptedRun(rows.getString("id"), rows.getString("session_id")));
                }
            }
            if (interrupted.isEmpty()) {
                return List.of();
            }
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, at.toString());
                statement.executeUpdate();
            }
            return interrupted;
        } catch (SQLException e) {
            throw new IllegalStateException("标记中断运行失败", e);
        }
    }
}
