package com.pilot.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.session.InMemoryRunCheckpointRepository;
import com.pilot.core.session.RunCheckpoint;
import com.pilot.core.session.RunCheckpointRepository;
import com.pilot.server.common.data.StorageHealth;

class RunStateTrackerTest {

    @Test
    void failedCheckpointWriteMarksTheRunUnreliableAndReportsDegradation() {
        RunCheckpointRepository repository = mock(RunCheckpointRepository.class);
        doThrow(new IllegalStateException("模拟数据库写入失败")).when(repository).save(any());
        RunStateTracker tracker = new RunStateTracker(repository, "instance");
        try {
            tracker.begin("session", "run", "/work");
            assertTrue(tracker.leaseLost("run"));
            assertTrue(StorageHealth.snapshot().issues().stream()
                .anyMatch(issue -> issue.area().equals("run-checkpoint") && issue.reason().contains("run")));
        } finally {
            StorageHealth.reset();
        }
    }

    private static ToolCompleted completed(String callId) {
        return new ToolCompleted(callId, "工具 write_file 成功：已写入 src/A.java", 12);
    }

    @Test
    void toolEventsAdvanceTheCheckpoint() {
        RunCheckpointRepository repository = new InMemoryRunCheckpointRepository();
        RunStateTracker tracker = new RunStateTracker(repository, "instance-a");

        tracker.begin("ses_1", "run_1", "/work");
        tracker.onEvent("ses_1", "run_1", new ToolStarted("call_1", "write_file", "{path=src/A.java}"));
        tracker.onEvent("ses_1", "run_1", completed("call_1"));

        RunCheckpoint checkpoint = tracker.current("ses_1").orElseThrow();
        assertEquals(1, checkpoint.step());
        assertEquals("call_1", checkpoint.lastToolCallId());
        assertTrue(checkpoint.lastSuccessfulStep().contains("工具调用成功"), checkpoint.lastSuccessfulStep());
        assertTrue(checkpoint.nextAction().contains("下一步"), checkpoint.nextAction());
        assertEquals("instance-a", checkpoint.owner());
    }

    @Test
    void completionKeepsTheRecordButReleasesTheLease() {
        RunCheckpointRepository repository = new InMemoryRunCheckpointRepository();
        RunStateTracker tracker = new RunStateTracker(repository, "instance-a");
        tracker.begin("ses_1", "run_1", "/work");

        tracker.onEvent("ses_1", "run_1",
            new RunCompleted("done", List.of("src/A.java"), List.of("mvn test → exit 0"), 1, 1, List.of()));
        tracker.finish("ses_1", "run_1", "COMPLETED");

        RunCheckpoint checkpoint = tracker.checkpoint("run_1").orElseThrow();
        assertEquals(RunCheckpoint.STATUS_COMPLETED, checkpoint.status());
        assertFalse(checkpoint.leaseActive(Instant.now().plusSeconds(1)));
        assertTrue(tracker.current("ses_1").isEmpty(), "完成的运行不应再算作活跃");
        assertFalse(tracker.resumable().stream().anyMatch(item -> item.runId().equals("run_1")));
    }

    @Test
    void failedRunStaysResumableWhenTheFailureIsRetryable() {
        RunCheckpointRepository repository = new InMemoryRunCheckpointRepository();
        RunStateTracker tracker = new RunStateTracker(repository, "instance-a");
        tracker.begin("ses_1", "run_1", "/work");
        tracker.onEvent("ses_1", "run_1", new RunFailed("MODEL_ERROR", "模型接口 503", true));

        RunCheckpoint checkpoint = tracker.current("ses_1").orElseThrow();
        assertEquals(RunCheckpoint.STATUS_INTERRUPTED, checkpoint.status());
        assertTrue(checkpoint.resumable());
        assertTrue(checkpoint.nextAction().contains("继续执行"), checkpoint.nextAction());
    }

    @Test
    void recoveryReportsWhereEachRunStoppedAndReleasesItsLease() {
        RunCheckpointRepository repository = new InMemoryRunCheckpointRepository();
        repository.save(RunCheckpoint
            .start("run_old", "ses_old", "/work/old", "instance-old", Instant.now().minusSeconds(5),
                Duration.ofSeconds(30))
            .advance(4, "call_7", "工具调用成功：mvn test → exit 0", "修复失败用例", RunCheckpoint.PHASE_MODEL, Instant.now()));

        RunStateTracker tracker = new RunStateTracker(repository, "instance-new");
        List<RunStateTracker.RecoveryEntry> entries = tracker.recoverAfterRestart();

        assertEquals(1, entries.size());
        RunStateTracker.RecoveryEntry entry = entries.getFirst();
        assertEquals("run_old", entry.runId());
        assertEquals("第 4 步", entry.step());
        assertTrue(entry.lastSuccessfulStep().contains("mvn test"));
        assertTrue(entry.nextAction().contains("修复失败用例"));
        assertTrue(entry.leaseState().contains("其他实例"), entry.leaseState());

        RunCheckpoint recovered = repository.find("run_old").orElseThrow();
        assertEquals(RunCheckpoint.STATUS_INTERRUPTED, recovered.status());
        assertNotNull(recovered.owner() == null ? "released" : recovered.owner());
        assertFalse(recovered.leasedByOther("instance-new", Instant.now()));
    }

    /**
     * 上次启动已经收敛过的运行（检查点标成 INTERRUPTED）不该在每次启动时重复上报：
     * 否则同一条旧账会在每一轮启动日志里出现，把真正的新中断淹掉。
     */
    @Test
    void recoveryDoesNotReportAlreadyConvergedRunsAgain() {
        RunCheckpointRepository repository = new InMemoryRunCheckpointRepository();
        repository.save(RunCheckpoint.start("run_old", "ses_old", "/work/old", "instance-old",
            Instant.now().minusSeconds(5), Duration.ofSeconds(30)));
        RunStateTracker tracker = new RunStateTracker(repository, "instance-new");

        assertEquals(1, tracker.recoverAfterRestart().size(), "第一次恢复要上报遗留运行");
        assertEquals(0, tracker.recoverAfterRestart().size(), "已经收敛过的不再重复上报");

        // 检查点本身保留：用户还要看得到「上次做到哪一步」
        RunCheckpoint checkpoint = repository.find("run_old").orElseThrow();
        assertEquals(RunCheckpoint.STATUS_INTERRUPTED, checkpoint.status());
    }

    @Test
    void leaseLossIsVisibleToDiagnostics() {
        RunCheckpointRepository repository = new InMemoryRunCheckpointRepository();
        RunStateTracker tracker = new RunStateTracker(repository, "instance-a");
        tracker.begin("ses_1", "run_1", "/work");

        // 另一个实例在租约过期后接管
        repository.releaseLease("run_1", RunCheckpoint.STATUS_RUNNING, Instant.now());
        tracker.heartbeat();

        assertNotNull(tracker.leaseState("run_1"));
        assertTrue(tracker.leaseLost("run_1"));
    }
}
