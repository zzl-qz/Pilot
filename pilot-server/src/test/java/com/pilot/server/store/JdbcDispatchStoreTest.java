package com.pilot.server.store;

import com.pilot.core.multi.TaskSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(store.finishAttempt(lease.attemptId(), lease.generation(), "succeeded", ""));

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
    }

    @Test
    void lateAttemptResultIsRejected() {
        JdbcDispatchStore store = store();
        store.saveDispatch("dsp_1", "ses_1", "run_1", "/tmp/ws", "running");
        JdbcDispatchStore.Lease first = store.startAttempt("dsp_1", "T1", "agent_1", "sbx_1");

        assertTrue(store.finishAttempt(first.attemptId(), first.generation(), "succeeded", ""));
        assertFalse(store.finishAttempt(first.attemptId(), first.generation(), "succeeded", ""),
                "同一代次的回执只能结算一次");

        JdbcDispatchStore.Lease second = store.startAttempt("dsp_1", "T1", "agent_2", "sbx_2");
        assertTrue(second.generation() > first.generation(), "重试必须拿到更大的代次");
        assertFalse(store.finishAttempt(first.attemptId(), first.generation(), "failed", "旧执行者"),
                "旧执行者的晚到结果不能改写状态");
        assertTrue(store.finishAttempt(second.attemptId(), second.generation(), "succeeded", ""));
    }

    @Test
    void restartMarksRunningAttemptsInterrupted() {
        JdbcDispatchStore store = store();
        store.saveDispatch("dsp_1", "ses_1", "run_1", "/tmp/ws", "running");
        store.startAttempt("dsp_1", "T1", "agent_1", "sbx_1");

        JdbcDispatchStore restarted = store();
        assertEquals(1, restarted.interruptStale("ses_1"));
        assertEquals("interrupted", restarted.attempts("dsp_1").get(0).status(),
                "重启后不能假装旧执行者还活着");
        assertEquals(0, restarted.interruptStale("ses_1"), "已收敛的不该重复处理");
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
