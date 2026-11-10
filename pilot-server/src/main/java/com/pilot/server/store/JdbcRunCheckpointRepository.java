package com.pilot.server.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.pilot.core.session.RunCheckpoint;
import com.pilot.core.session.RunCheckpointRepository;

/**
 * 运行检查点落库。
 *
 * <p>租约抢占用一条带条件的 UPDATE 完成：条件里同时接受「没有持有者」「自己续租」
 * 「上一个租约已经过期」，因此不需要额外的分布式锁，也不会出现两个实例同时接管。
 */
public final class JdbcRunCheckpointRepository implements RunCheckpointRepository {
    private final SqliteStore store;

    public JdbcRunCheckpointRepository(SqliteStore store) {
        this.store = store;
    }

    @Override
    public void save(RunCheckpoint checkpoint) {
        JdbcSupport.update(store, """
            INSERT INTO run_checkpoints (run_id, session_id, phase, step, last_tool_call_id,
                last_successful_step, next_action, workspace, status, owner, lease_expires_at,
                heartbeat_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(run_id) DO UPDATE SET
                phase = excluded.phase, step = excluded.step,
                last_tool_call_id = excluded.last_tool_call_id,
                last_successful_step = excluded.last_successful_step,
                next_action = excluded.next_action, workspace = excluded.workspace,
                status = excluded.status, owner = excluded.owner,
                lease_expires_at = excluded.lease_expires_at,
                heartbeat_at = excluded.heartbeat_at, updated_at = excluded.updated_at
            """, statement -> {
            statement.setString(1, checkpoint.runId());
            statement.setString(2, checkpoint.sessionId());
            statement.setString(3, checkpoint.phase());
            statement.setInt(4, checkpoint.step());
            statement.setString(5, checkpoint.lastToolCallId());
            statement.setString(6, checkpoint.lastSuccessfulStep());
            statement.setString(7, checkpoint.nextAction());
            statement.setString(8, checkpoint.workspace());
            statement.setString(9, checkpoint.status());
            statement.setString(10, checkpoint.owner());
            statement.setString(11, text(checkpoint.leaseExpiresAt()));
            statement.setString(12, text(checkpoint.heartbeatAt()));
            statement.setString(13, text(checkpoint.updatedAt()));
        }, "保存运行检查点失败: " + checkpoint.runId());
    }

    @Override
    public Optional<RunCheckpoint> find(String runId) {
        return JdbcSupport.queryOne(store, "SELECT * FROM run_checkpoints WHERE run_id = ?",
            statement -> statement.setString(1, runId), JdbcRunCheckpointRepository::map, "读取运行检查点失败: " + runId);
    }

    @Override
    public Optional<RunCheckpoint> activeForSession(String sessionId) {
        String updatedAt = SqliteStore.normalizedInstantSql("updated_at");
        return JdbcSupport.queryOne(store, """
            SELECT * FROM run_checkpoints WHERE session_id = ?
              AND status IN ('RUNNING', 'INTERRUPTED', 'PAUSED')
            ORDER BY %s DESC LIMIT 1
            """.formatted(updatedAt), statement -> statement.setString(1, sessionId), JdbcRunCheckpointRepository::map,
            "读取会话检查点失败: " + sessionId);
    }

    @Override
    public List<RunCheckpoint> resumable() {
        String updatedAt = SqliteStore.normalizedInstantSql("updated_at");
        return JdbcSupport.query(store, """
            SELECT * FROM run_checkpoints WHERE status IN ('RUNNING', 'INTERRUPTED', 'PAUSED')
            ORDER BY %s ASC
            """.formatted(updatedAt), statement -> {
        }, JdbcRunCheckpointRepository::map, "读取可恢复检查点失败");
    }

    @Override
    public boolean claimLease(String runId, String owner, Instant now, Duration lease) {
        Instant at = now == null ? Instant.now() : now;
        Duration span = lease == null ? Duration.ofSeconds(30) : lease;
        String leaseExpiry = SqliteStore.normalizedInstantSql("lease_expires_at");
        int updated = JdbcSupport.update(store, """
            UPDATE run_checkpoints
               SET owner = ?, lease_expires_at = ?, heartbeat_at = ?, updated_at = ?
             WHERE run_id = ?
               AND (owner IS NULL OR owner = ? OR lease_expires_at IS NULL OR %s < ?)
            """.formatted(leaseExpiry), statement -> {
            statement.setString(1, owner);
            statement.setString(2, at.plus(span).toString());
            statement.setString(3, at.toString());
            statement.setString(4, at.toString());
            statement.setString(5, runId);
            statement.setString(6, owner);
            statement.setString(7, at.toString());
        }, "抢占运行租约失败: " + runId);
        return updated > 0;
    }

    @Override
    public boolean renewLease(String runId, String owner, Instant now, Duration lease) {
        Instant at = now == null ? Instant.now() : now;
        Duration span = lease == null ? Duration.ofSeconds(30) : lease;
        int updated = JdbcSupport.update(store, """
            UPDATE run_checkpoints SET lease_expires_at = ?, heartbeat_at = ?, updated_at = ?
             WHERE run_id = ? AND owner = ?
            """, statement -> {
            statement.setString(1, at.plus(span).toString());
            statement.setString(2, at.toString());
            statement.setString(3, at.toString());
            statement.setString(4, runId);
            statement.setString(5, owner);
        }, "续租运行检查点失败: " + runId);
        return updated > 0;
    }

    @Override
    public void releaseLease(String runId, String status, Instant now) {
        Instant at = now == null ? Instant.now() : now;
        JdbcSupport.update(store, """
            UPDATE run_checkpoints SET status = ?, owner = NULL, lease_expires_at = NULL,
                   updated_at = ? WHERE run_id = ?
            """, statement -> {
            statement.setString(1, status == null ? RunCheckpoint.STATUS_COMPLETED : status);
            statement.setString(2, at.toString());
            statement.setString(3, runId);
        }, "释放运行租约失败: " + runId);
    }

    @Override
    public int deleteBySession(String sessionId) {
        return JdbcSupport.update(store, "DELETE FROM run_checkpoints WHERE session_id = ?",
            statement -> statement.setString(1, sessionId), "删除会话检查点失败: " + sessionId);
    }

    private static RunCheckpoint map(ResultSet rows) throws SQLException {
        return new RunCheckpoint(rows.getString("run_id"), rows.getString("session_id"), rows.getString("phase"),
            rows.getInt("step"), rows.getString("last_tool_call_id"), rows.getString("last_successful_step"),
            rows.getString("next_action"), rows.getString("workspace"), rows.getString("status"),
            rows.getString("owner"), instant(rows.getString("lease_expires_at")),
            instant(rows.getString("heartbeat_at")), instant(rows.getString("updated_at")));
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }
}
