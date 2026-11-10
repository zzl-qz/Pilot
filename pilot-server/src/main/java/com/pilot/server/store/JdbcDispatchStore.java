package com.pilot.server.store;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import com.pilot.core.multi.TaskSpec;

/**
 * 派发图与执行记录的持久化：{@code dispatches} / {@code task_nodes} / {@code task_attempts}。
 *
 * <p>v1 只落「调度必需」的字段：图、节点状态、每次尝试与租约代次。内存看板与 JSON 副本继续
 * 用于界面展示，但重启后能否找回待办、谁在执行、执行到第几次，一律以这里为准。
 *
 * <p>没有配置持久化存储时（测试或 {@code store} 缺失）所有方法都是空操作，
 * 调用方不需要到处判空语义，只需要知道「记录了但可能查不回来」。
 */
@Component
public final class JdbcDispatchStore {

    /** 一次派发。 */
    public record DispatchRow(String dispatchId, String sessionId, String runId, String workspace, String status,
        String createdAt, String updatedAt) {
    }

    /** 图上的一个节点；状态由调度器推进。 */
    public record NodeRow(String dispatchId, String taskId, String title, String goal, String dependsOn, int writable,
        String risk, int reviewers, String status, String agentId, String detail, int attempts, String updatedAt) {
    }

    /** 单次尝试；{@code generation} 是租约代次，旧执行者的晚到结果写不进来。 */
    public record AttemptRow(String attemptId, String dispatchId, String taskId, String agentId, String sandboxId,
        int generation, String status, String failure, String startedAt, String finishedAt, Usage usage) {
    }

    /** 新开一次尝试时拿到的租约：代次越大越新。 */
    public record Lease(String attemptId, int generation) {
        public static final Lease NONE = new Lease("", 0);
    }

    /**
     * 一次尝试的模型用量（per-agent 归集的最小单位）。
     *
     * @param exact false 表示端点没返回 usage、这里是本地估算，界面必须标成「估算」而不是 0
     */
    public record Usage(long inputTokens, long outputTokens, long requests, boolean exact) {
        public static final Usage EMPTY = new Usage(0, 0, 0, true);

        public Usage merge(Usage other) {
            if (other == null) {
                return this;
            }
            return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                requests + other.requests, exact && other.exact);
        }

        /** 预算口径：输入 + 输出。 */
        public long total() {
            return inputTokens + outputTokens;
        }
    }

    private static final String[] SCHEMA = {"""
        CREATE TABLE IF NOT EXISTS dispatches (
            dispatch_id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            run_id TEXT NOT NULL,
            workspace TEXT,
            status TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )""", """
        CREATE TABLE IF NOT EXISTS task_nodes (
            dispatch_id TEXT NOT NULL,
            task_id TEXT NOT NULL,
            title TEXT,
            goal TEXT,
            depends_on TEXT,
            writable INTEGER NOT NULL DEFAULT 0,
            risk TEXT,
            reviewers INTEGER NOT NULL DEFAULT 1,
            status TEXT NOT NULL,
            agent_id TEXT,
            detail TEXT,
            attempts INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (dispatch_id, task_id)
        )""", """
        CREATE TABLE IF NOT EXISTS task_attempts (
            attempt_id TEXT PRIMARY KEY,
            dispatch_id TEXT NOT NULL,
            task_id TEXT NOT NULL,
            agent_id TEXT,
            sandbox_id TEXT,
            lane TEXT NOT NULL DEFAULT 'main',
            generation INTEGER NOT NULL,
            status TEXT NOT NULL,
            failure TEXT,
            started_at TEXT NOT NULL,
            finished_at TEXT,
            input_tokens INTEGER NOT NULL DEFAULT 0,
            output_tokens INTEGER NOT NULL DEFAULT 0,
            requests INTEGER NOT NULL DEFAULT 0,
            exact INTEGER NOT NULL DEFAULT 1
        )""", "CREATE INDEX IF NOT EXISTS idx_dispatches_session ON dispatches(session_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_task_attempts_task ON task_attempts(task_id, generation)"};

    /** 后补的列：表结构演进时只往后加，不改已有列。 */
    private static final String[][] ADDED_COLUMNS = {{"task_attempts", "input_tokens", "INTEGER NOT NULL DEFAULT 0"},
        {"task_attempts", "output_tokens", "INTEGER NOT NULL DEFAULT 0"},
        {"task_attempts", "requests", "INTEGER NOT NULL DEFAULT 0"},
        {"task_attempts", "exact", "INTEGER NOT NULL DEFAULT 1"},
        {"task_attempts", "lane", "TEXT NOT NULL DEFAULT 'main'"}};

    private final SqliteStore store;

    public JdbcDispatchStore(ObjectProvider<SqliteStore> store) {
        SqliteStore resolved = store == null ? null : store.getIfAvailable();
        this.store = resolved;
        if (resolved != null) {
            for (String ddl : SCHEMA) {
                JdbcSupport.update(resolved, ddl, statement -> {
                }, "初始化派发表失败");
            }
            // 老库补列：表已经存在时 CREATE TABLE IF NOT EXISTS 不会改动它
            for (String[] column : ADDED_COLUMNS) {
                addColumnIfMissing(resolved, column[0], column[1], column[2]);
            }
        }
    }

    private static void addColumnIfMissing(SqliteStore store, String table, String column, String definition) {
        boolean present = JdbcSupport.query(store, "PRAGMA table_info(" + table + ")", statement -> {
        }, rows -> rows.getString("name"), "检查派发表结构失败").contains(column);
        if (!present) {
            JdbcSupport.update(store, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition,
                statement -> {
                }, "补充派发表列失败");
        }
    }

    public boolean available() {
        return store != null;
    }

    public void saveDispatch(String dispatchId, String sessionId, String runId, String workspace, String status) {
        if (!available()) {
            return;
        }
        String now = Instant.now().toString();
        JdbcSupport.update(store, """
            INSERT INTO dispatches (dispatch_id, session_id, run_id, workspace, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(dispatch_id) DO UPDATE SET status = excluded.status, updated_at = excluded.updated_at
            """, statement -> {
            statement.setString(1, dispatchId);
            statement.setString(2, sessionId);
            statement.setString(3, runId);
            statement.setString(4, workspace);
            statement.setString(5, status);
            statement.setString(6, now);
            statement.setString(7, now);
        }, "保存派发记录失败");
    }

    public void saveNodes(String dispatchId, List<TaskSpec> specs) {
        if (!available() || specs == null) {
            return;
        }
        String now = Instant.now().toString();
        JdbcSupport.inTransaction(store, connection -> {
            for (TaskSpec spec : specs) {
                JdbcSupport.exec(connection, """
                    INSERT INTO task_nodes (dispatch_id, task_id, title, goal, depends_on, writable,
                                            risk, reviewers, status, agent_id, detail, attempts, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    ON CONFLICT(dispatch_id, task_id) DO UPDATE SET
                        title = excluded.title, goal = excluded.goal, depends_on = excluded.depends_on,
                        writable = excluded.writable, risk = excluded.risk, reviewers = excluded.reviewers
                    """, statement -> {
                    statement.setString(1, dispatchId);
                    statement.setString(2, spec.id());
                    statement.setString(3, spec.title());
                    statement.setString(4, spec.goal());
                    statement.setString(5, String.join(",", spec.dependsOn()));
                    statement.setInt(6, spec.scope() != null && spec.scope().writable() ? 1 : 0);
                    statement.setString(7, spec.risk() == null ? "" : spec.risk().wireName());
                    statement.setInt(8, spec.reviewers());
                    statement.setString(9, "pending");
                    statement.setString(10, "");
                    statement.setString(11, "");
                    statement.setString(12, now);
                });
            }
        }, "保存任务节点失败");
    }

    public void updateNode(String dispatchId, String taskId, String status, String agentId, String detail,
        int attempts) {
        if (!available()) {
            return;
        }
        JdbcSupport.update(store, """
            UPDATE task_nodes SET status = ?, agent_id = ?, detail = ?, attempts = ?, updated_at = ?
            WHERE dispatch_id = ? AND task_id = ?
            """, statement -> {
            statement.setString(1, status);
            statement.setString(2, agentId == null ? "" : agentId);
            statement.setString(3, detail == null ? "" : detail);
            statement.setInt(4, attempts);
            statement.setString(5, Instant.now().toString());
            statement.setString(6, dispatchId);
            statement.setString(7, taskId);
        }, "更新任务节点失败");
    }

    /** 开一次新的尝试：同一任务每重试一次，代次加一，旧代次的完成回执会被拒绝。 */
    public Lease startAttempt(String dispatchId, String taskId, String agentId, String sandboxId) {
        return startAttempt(dispatchId, taskId, agentId, sandboxId, "main");
    }

    /** 同任务的并行方案各有 lane；只替换同一方案的旧重试，不能取消其他审阅者。 */
    public Lease startAttempt(String dispatchId, String taskId, String agentId, String sandboxId, String lane) {
        if (!available()) {
            return Lease.NONE;
        }
        int[] generation = {0};
        String attemptId = "att_" + UUID.randomUUID();
        JdbcSupport.inTransaction(store, connection -> {
            // 先取 SQLite 写锁并使旧执行者失效，再在同一事务内分配代次。
            JdbcSupport.exec(connection, """
                UPDATE task_attempts SET status = 'interrupted', failure = '已被新尝试取代', finished_at = ?
                WHERE dispatch_id = ? AND task_id = ? AND lane = ? AND status = 'running'
                """, statement -> {
                statement.setString(1, Instant.now().toString());
                statement.setString(2, dispatchId);
                statement.setString(3, taskId);
                statement.setString(4, lane);
            });
            try (var statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(generation), 0) + 1 AS gen FROM task_attempts WHERE dispatch_id = ? AND task_id = ? AND lane = ?")) {
                statement.setString(1, dispatchId);
                statement.setString(2, taskId);
                statement.setString(3, lane);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    generation[0] = rows.getInt("gen");
                }
            }
            JdbcSupport.exec(connection, """
                INSERT INTO task_attempts (attempt_id, dispatch_id, task_id, agent_id, sandbox_id,
                                           generation, status, failure, started_at, finished_at, lane)
                VALUES (?, ?, ?, ?, ?, ?, 'running', '', ?, NULL, ?)
                """, statement -> {
                statement.setString(1, attemptId);
                statement.setString(2, dispatchId);
                statement.setString(3, taskId);
                statement.setString(4, agentId == null ? "" : agentId);
                statement.setString(5, sandboxId == null ? "" : sandboxId);
                statement.setInt(6, generation[0]);
                statement.setString(7, Instant.now().toString());
                statement.setString(8, lane);
            });
        }, "记录任务尝试失败");
        return new Lease(attemptId, generation[0]);
    }

    /**
     * 结算一次尝试。只有「代次匹配且仍在运行」的行会被更新。
     *
     * @return {@code false} 表示这次回执来得太晚（旧执行者、被取消或已被新尝试取代），调用方应丢弃结果
     */
    public boolean finishAttempt(String attemptId, int generation, String status, String failure, Usage usage) {
        if (!available() || attemptId == null || attemptId.isBlank()) {
            return true;
        }
        Usage counted = usage == null ? Usage.EMPTY : usage;
        int updated = JdbcSupport.update(store, """
            UPDATE task_attempts SET status = ?, failure = ?, finished_at = ?,
                    input_tokens = ?, output_tokens = ?, requests = ?, exact = ?
            WHERE attempt_id = ? AND generation = ? AND status = 'running'
            """, statement -> {
            statement.setString(1, status);
            statement.setString(2, failure == null ? "" : failure);
            statement.setString(3, Instant.now().toString());
            statement.setLong(4, counted.inputTokens());
            statement.setLong(5, counted.outputTokens());
            statement.setLong(6, counted.requests());
            statement.setInt(7, counted.exact() ? 1 : 0);
            statement.setString(8, attemptId);
            statement.setInt(9, generation);
        }, "结算任务尝试失败");
        return updated > 0;
    }

    /** 重启收敛：上次进程留下的「仍在运行」一律标成中断，不假装旧执行者还活着。 */
    public int interruptStale(String sessionId) {
        if (!available()) {
            return 0;
        }
        String now = Instant.now().toString();
        int[] count = {0};
        JdbcSupport.inTransaction(store, connection -> {
            count[0] = JdbcSupport.exec(connection, """
                UPDATE task_attempts SET status = 'interrupted', failure = '进程重启，执行者已失效',
                        finished_at = ?
                WHERE status = 'running' AND dispatch_id IN (
                    SELECT dispatch_id FROM dispatches WHERE session_id = ?)
                """, statement -> {
                statement.setString(1, now);
                statement.setString(2, sessionId);
            });
            JdbcSupport.exec(connection, """
                UPDATE task_nodes SET status = 'cancelled', detail = '进程重启，需重新派发', updated_at = ?
                WHERE status IN ('pending', 'running', 'ready', 'verifying') AND dispatch_id IN (
                    SELECT dispatch_id FROM dispatches WHERE session_id = ? AND status = 'running')
                """, statement -> {
                statement.setString(1, now);
                statement.setString(2, sessionId);
            });
            JdbcSupport.exec(connection, """
                UPDATE dispatches SET status = 'interrupted', updated_at = ?
                WHERE session_id = ? AND status = 'running'
                """, statement -> {
                statement.setString(1, now);
                statement.setString(2, sessionId);
            });
        }, "收敛中断的派发失败");
        return count[0];
    }

    @org.springframework.context.event.EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        if (!available()) {
            return;
        }
        for (String session : JdbcSupport.query(store,
            "SELECT DISTINCT session_id FROM dispatches WHERE status = 'running'", statement -> {
            }, rows -> rows.getString("session_id"), "查询待收敛派发失败")) {
            interruptStale(session);
        }
    }

    public List<DispatchRow> dispatches(String sessionId) {
        if (!available()) {
            return List.of();
        }
        String createdAt = SqliteStore.normalizedInstantSql("created_at");
        return JdbcSupport.query(store, """
            SELECT dispatch_id, session_id, run_id, workspace, status, created_at, updated_at
            FROM dispatches WHERE session_id = ? ORDER BY %s
            """.formatted(createdAt), statement -> statement.setString(1, sessionId),
            rows -> new DispatchRow(rows.getString("dispatch_id"), rows.getString("session_id"),
                rows.getString("run_id"), rows.getString("workspace"), rows.getString("status"),
                rows.getString("created_at"), rows.getString("updated_at")),
            "查询派发记录失败");
    }

    public List<NodeRow> nodes(String dispatchId) {
        if (!available()) {
            return List.of();
        }
        return JdbcSupport.query(store, """
            SELECT dispatch_id, task_id, title, goal, depends_on, writable, risk, reviewers, status,
                   agent_id, detail, attempts, updated_at
            FROM task_nodes WHERE dispatch_id = ? ORDER BY task_id
            """, statement -> statement.setString(1, dispatchId),
            rows -> new NodeRow(rows.getString("dispatch_id"), rows.getString("task_id"), rows.getString("title"),
                rows.getString("goal"), rows.getString("depends_on"), rows.getInt("writable"), rows.getString("risk"),
                rows.getInt("reviewers"), rows.getString("status"), rows.getString("agent_id"),
                rows.getString("detail"), rows.getInt("attempts"), rows.getString("updated_at")),
            "查询任务节点失败");
    }

    public List<AttemptRow> attempts(String dispatchId) {
        if (!available()) {
            return List.of();
        }
        return JdbcSupport
            .query(store, """
                SELECT attempt_id, dispatch_id, task_id, agent_id, sandbox_id, generation, status, failure,
                       started_at, finished_at, input_tokens, output_tokens, requests, exact
                FROM task_attempts WHERE dispatch_id = ? ORDER BY task_id, generation
                """, statement -> statement.setString(1, dispatchId),
                rows -> new AttemptRow(rows.getString("attempt_id"), rows.getString("dispatch_id"),
                    rows.getString("task_id"), rows.getString("agent_id"), rows.getString("sandbox_id"),
                    rows.getInt("generation"), rows.getString("status"), rows.getString("failure"),
                    rows.getString("started_at"), rows.getString("finished_at"), new Usage(rows.getLong("input_tokens"),
                        rows.getLong("output_tokens"), rows.getLong("requests"), rows.getInt("exact") == 1)),
                "查询任务尝试失败");
    }
}
