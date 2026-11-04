package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.core.multi.TaskSpec;

/** 派发图与尝试记录的持久化：重启后能找回待办，过期执行者的回执被丢弃。 */
class JdbcDispatchStoreTest {

    @TempDir
    Path dir;

    private JdbcDispatchStore store() {
        SqliteStore sqlite = new SqliteStore(dir.resolve("pilot.db"));
        ObjectProvider<SqliteStore> provider = new ObjectProvider<>() {
            @Override
            public SqliteStore getObject() {
                return sqlite;
            }

            @Override
            public SqliteStore getIfAvailable() {
                return sqlite;
            }
        };
        return new JdbcDispatchStore(provider);
    }

    private static TaskSpec node(String id) {
        return TaskSpec.readOnly(id, "任务 " + id, "目标 " + id, List.of(), List.of("src/**"));
    }

    @Test
    void persistsGraphNodesAndAttempts() {
        JdbcDispatchStore store = store();
        store.saveDispatch("dsp_1", "ses_1", "run_1", "/tmp/ws", "running");
        store.saveNodes("dsp_1", List.of(node("T1")));
        store.updateNode("dsp_1", "T1", "done", "agent_1", "完成", 1);

        JdbcDispatchStore.Lease lease = store.startAttempt("dsp_1", "T1", "agent_1", "sbx_1");
        assertTrue(store.finishAttempt(lease.attemptId(), lease.generation(), "succeeded", "",
            new JdbcDispatchStore.Usage(1200, 300, 2, true)));

        List<JdbcDispatchStore.DispatchRow> dispatches = store.dispatches("ses_1");
        assertEquals(1, dispatches.size());
        assertEquals("dsp_1", dispatches.get(0).dispatchId());

        List<JdbcDispatchStore.NodeRow> nodes = store.nodes("dsp_1");
        assertEquals(1, nodes.size());
        assertEquals("done", nodes.get(0).status());
        assertEquals("agent_1", nodes.get(0).agentId());
        assertEquals(1, nodes.get(0).attempts());

        List<JdbcDispatchStore.AttemptRow> attempts = store.attempts("dsp_1");
        assertEquals(1, attempts.size());
        assertEquals("succeeded", attempts.get(0).status());
        assertEquals("sbx_1", attempts.get(0).sandboxId());
        assertEquals(1200, attempts.get(0).usage().inputTokens(), "用量要能按尝试查回来");
        assertEquals(300, attempts.get(0).usage().outputTokens());
        assertEquals(2, attempts.get(0).usage().requests());
        assertTrue(attempts.get(0).usage().exact());
    }

    @Test
    void lateAttemptResultIsRejected() {
        JdbcDispatchStore store = store();
        store.saveDispatch("dsp_1", "ses_1", "run_1", "/tmp/ws", "running");
        JdbcDispatchStore.Lease first = store.startAttempt("dsp_1", "T1", "agent_1", "sbx_1");

        assertTrue(
            store.finishAttempt(first.attemptId(), first.generation(), "succeeded", "", JdbcDispatchStore.Usage.EMPTY));
        assertFalse(
            store.finishAttempt(first.attemptId(), first.generation(), "succeeded", "", JdbcDispatchStore.Usage.EMPTY),
            "同一代次的回执只能结算一次");

        JdbcDispatchStore.Lease second = store.startAttempt("dsp_1", "T1", "agent_2", "sbx_2");
        assertTrue(second.generation() > first.generation(), "重试必须拿到更大的代次");
        assertFalse(
            store.finishAttempt(first.attemptId(), first.generation(), "failed", "旧执行者", JdbcDispatchStore.Usage.EMPTY),
            "旧执行者的晚到结果不能改写状态");
        assertTrue(store.finishAttempt(second.attemptId(), second.generation(), "succeeded", "",
            JdbcDispatchStore.Usage.EMPTY));
    }

    @Test
    void restartMarksRunningAttemptsInterrupted() {
        JdbcDispatchStore store = store();
        store.saveDispatch("dsp_1", "ses_1", "run_1", "/tmp/ws", "running");
        store.startAttempt("dsp_1", "T1", "agent_1", "sbx_1");

        JdbcDispatchStore restarted = store();
        assertEquals(1, restarted.interruptStale("ses_1"));
        assertEquals("interrupted", restarted.attempts("dsp_1").get(0).status(), "重启后不能假装旧执行者还活着");
        assertEquals(0, restarted.interruptStale("ses_1"), "已收敛的不该重复处理");
    }

    @Test
    void startingRetryFencesStillRunningAttemptAndScopesGenerationToDispatch() {
        JdbcDispatchStore store = store();
        store.saveDispatch("d1", "s", "r", "", "running");
        store.saveDispatch("d2", "s", "r", "", "running");
        var first = store.startAttempt("d1", "T1", "a1", "");
        var retry = store.startAttempt("d1", "T1", "a2", "");
        var independent = store.startAttempt("d2", "T1", "a3", "");
        assertEquals(1, independent.generation());
        assertEquals(2, retry.generation());
        assertFalse(
            store.finishAttempt(first.attemptId(), first.generation(), "succeeded", "", JdbcDispatchStore.Usage.EMPTY),
            "旧执行者尚未完成就被重试取代，其结果也必须拒绝");
        assertTrue(
            store.finishAttempt(retry.attemptId(), retry.generation(), "succeeded", "", JdbcDispatchStore.Usage.EMPTY));
        assertTrue(store.finishAttempt(independent.attemptId(), independent.generation(), "succeeded", "",
            JdbcDispatchStore.Usage.EMPTY));
    }

    @Test
    void parallelReviewLanesDoNotFenceEachOther() {
        var store = store();
        var left = store.startAttempt("d", "T1", "a", "s1", "left");
        var right = store.startAttempt("d", "T1", "b", "s2", "right");
        var retried = store.startAttempt("d", "T1", "c", "s3", "left");
        assertEquals(1, right.generation());
        assertEquals(2, retried.generation());
        assertFalse(
            store.finishAttempt(left.attemptId(), left.generation(), "succeeded", "", JdbcDispatchStore.Usage.EMPTY));
        assertTrue(
            store.finishAttempt(right.attemptId(), right.generation(), "succeeded", "", JdbcDispatchStore.Usage.EMPTY));
        assertTrue(store.finishAttempt(retried.attemptId(), retried.generation(), "succeeded", "",
            JdbcDispatchStore.Usage.EMPTY));
    }

    @Test
    void startupRecoveryClosesDispatchAndUnfinishedNodesWithoutNewDispatch() {
        JdbcDispatchStore store = store();
        store.saveDispatch("d1", "s", "r", "", "running");
        store.saveNodes("d1", List.of(node("T1"), node("T2"), node("T3")));
        store.updateNode("d1", "T1", "done", "a", "", 1);
        store.updateNode("d1", "T2", "running", "b", "", 1);
        store.startAttempt("d1", "T2", "b", "");
        store.recoverInterrupted();
        assertEquals("interrupted", store.dispatches("s").get(0).status());
        assertEquals(List.of("done", "cancelled", "cancelled"),
            store.nodes("d1").stream().map(JdbcDispatchStore.NodeRow::status).toList());
        assertEquals("interrupted", store.attempts("d1").get(0).status());
    }

    @Test
    void oldAttemptTableIsMigratedWithoutLosingUsage() throws Exception {
        var original = store();
        var first = original.startAttempt("d", "T1", "a", "s");
        original.finishAttempt(first.attemptId(), first.generation(), "succeeded", "",
            new JdbcDispatchStore.Usage(100, 20, 1, false));
        try (var connection = new SqliteStore(dir.resolve("pilot.db")).open();
            var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE task_attempts DROP COLUMN lane");
        }
        var upgraded = store();
        assertEquals(2, upgraded.startAttempt("d", "T1", "b", "s2").generation());
        assertEquals(100, upgraded.attempts("d").get(0).usage().inputTokens());
        assertFalse(upgraded.attempts("d").get(0).usage().exact());
    }

    @Test
    void worksWithoutPersistedStore() {
        JdbcDispatchStore absent = new JdbcDispatchStore(null);

        absent.saveDispatch("dsp_1", "ses_1", "run_1", "/tmp/ws", "running");
        absent.saveNodes("dsp_1", List.of(node("T1")));
        absent.updateNode("dsp_1", "T1", "done", "agent_1", "完成", 1);
        assertEquals(JdbcDispatchStore.Lease.NONE, absent.startAttempt("dsp_1", "T1", "agent_1", "sbx_1"));

        assertFalse(absent.available());
        assertTrue(absent.dispatches("ses_1").isEmpty());
        assertTrue(absent.attempts("dsp_1").isEmpty());
        assertEquals(0, absent.interruptStale("ses_1"));
    }
}
