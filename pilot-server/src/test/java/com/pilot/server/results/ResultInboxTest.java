package com.pilot.server.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.schedule.ScheduleService;
import com.pilot.server.schedule.ScheduledTask;
import com.pilot.server.session.TaskControlService;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.ResultInboxRepository;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

class ResultInboxTest {
    @TempDir
    Path root;
    final JsonMapper mapper = JsonMapper.builder().build();
    SessionService sessions;
    ResultInboxRepository repository;
    ScheduleService schedules;
    TaskControlService tasks;
    ResultInboxService service;
    SqliteStore store;

    @BeforeEach
    void setup() {
        store = new SqliteStore(root.resolve("results.db"));
        var codec = new EventCodec(mapper);
        sessions = new SessionService(Clock.systemUTC(), new JdbcSessionRepository(store, codec));
        repository = new ResultInboxRepository(store, codec, sessions);
        schedules = mock(ScheduleService.class);
        when(schedules.list()).thenReturn(List.of());
        tasks = new TaskControlService(mapper, new PilotDataDir(root.resolve("data").toString()));
        service = new ResultInboxService(repository, sessions, schedules, tasks, mapper);
    }

    @Test
    void persistsReceiptsWithoutConfusingReadingWithHandlingAndRejectsStaleRevision() {
        String id = sessions.createSession("修复任务").id();
        sessions.publish(id, "run_a", new RunCompleted("已完成", List.of("a.txt"), List.of("mvn test")));
        var item = service.get("run_a");
        assertFalse(item.read());
        assertFalse(item.handled());
        assertTrue(service.mark(item.id(), item.revision(), "read").read());
        assertFalse(service.get(item.id()).handled());
        assertTrue(service.mark(item.id(), item.revision(), "handle").handled());
        var reopened =
            new ResultInboxRepository(new SqliteStore(root.resolve("results.db")), new EventCodec(mapper), sessions);
        assertTrue(reopened.receipt(item.id(), item.revision()).handled());
        service.mark(item.id(), item.revision(), "reopen");
        assertFalse(service.get(item.id()).handled());
        service.mark(item.id(), item.revision(), "unread");
        assertFalse(service.get(item.id()).read());
        sessions.publish(id, "run_a", new RunCompleted("更新后结果", List.of(), List.of()));
        assertEquals(1, service.list().items().size());
        assertThrows(IllegalStateException.class, () -> service.mark(item.id(), item.revision(), "handle"));
        sessions.delete(id);
        assertTrue(service.list().items().isEmpty());
    }

    @Test
    void excludesSideChatsWaitingAndCancellationButKeepsFailureAndOpenSteps() {
        String id = sessions.createSession("主任务").id();
        String side = sessions.createSession("侧边任务", null, null, false, id).id();
        sessions.publish(side, "side", new RunCompleted("侧边结果", List.of(), List.of()));
        sessions.publish(id, "waiting", new RunFailed("WAITING", "等待外部结果", false));
        sessions.publish(id, "paused", new RunFailed("PAUSED", "暂停", false));
        sessions.publish(id, "failed", new RunFailed("MODEL_ERROR", "连接失败", false));
        sessions.publish(id, "open", new RunCompleted("部分完成", List.of(), List.of(), 1, 2, List.of("验证部署")));
        assertEquals(Set.of("failed", "open"),
            new HashSet<>(service.list().items().stream().map(ResultInboxService.Item::id).toList()));
        assertEquals("AWAITING_REVIEW", service.get("open").state());
    }

    @Test
    void copiedForkHistoryDoesNotDuplicateOrStealTheOriginalRunResult() {
        String original = sessions.createSession("原任务").id();
        sessions.publish(original, "original_run", new RunFailed("MODEL_ERROR", "旧错误", false));
        sessions.publish(original, "original_run", new RunCompleted("原任务最终结果", List.of(), List.of()));
        String fork = sessions.createSession("分支任务").id();
        sessions.events(original).forEach(event -> sessions.publish(fork, event.runId(), event.payload()));
        var items = service.list().items();
        assertEquals(1, items.size());
        assertEquals(original, items.getFirst().sessionId());
        assertEquals("原任务最终结果", items.getFirst().summary());
    }

    @Test
    void scheduledAttemptsCollapseIntoOneResultAndHandoffDoesNotResetReceipt() {
        String id = sessions.createSession("独立任务").id();
        sessions.publish(id, "attempt1", new RunFailed("MODEL_ERROR", "重试中", false));
        sessions.publish(id, "attempt2", new RunCompleted("最终结果", List.of("a.txt"), List.of("测试通过")));
        var job = new ScheduledTask("schedule_a", "定时结果", id, null, "修复", null, null, Instant.now(), "UTC", "COMPLETED",
            "attempt2", 2, 1, null, "已完成", List.of(), 5,
            new ScheduledTask.Workspace("ISOLATED", "copy", "base", "HEAD", "commit", null));
        when(schedules.list()).thenReturn(List.of(job));
        var item = service.get(job.id());
        assertEquals(1, service.list().items().size());
        assertEquals("最终结果", item.summary());
        service.mark(item.id(), item.revision(), "handle");
        when(schedules.list()).thenReturn(List.of(job
            .withWorkspace(new ScheduledTask.Workspace("ISOLATED", "copy", "base", "HEAD", "commit", Instant.now()))));
        assertTrue(service.get(item.id()).handled());
        when(schedules.list()).thenReturn(List
            .of(job.change("NEEDS_ATTENTION", null, job.runId(), job.runAt(), null, 2, 1, "执行目录已丢失", job.history())));
        assertTrue(service.get(item.id()).summary().startsWith("执行目录已丢失"));
        assertFalse(service.get(item.id()).read());
    }

    @Test
    void historicalResearchUsesOutcomeSnapshotAndMalformedRecordsHaveReadableFallback() {
        String id = sessions.createSession("调研任务").id();
        tasks.start(id, "原目标", List.of(), List.of());
        var valid = new TaskControlService.Evidence("e1", "record_research", "",
            mapper.writeValueAsString(Map.of("sources", List.of("https://github.com/openclaw/openclaw"), "decision",
                "复用既有链路", "license", "已核对", "sourceEvidence", List.of("fetch1"))),
            "v", null);
        var old = new TaskControlService.Evidence("e2", "record_research", "", "{旧的截断记录", "v", null);
        tasks.execution(id, tasks.load(id), new TaskControlService.Execution("GOAL", "", List.of(), List.of(), 1, 1L,
            List.of(valid, old), "VERIFIED", "", 0, ""));
        tasks.snapshotResult(id, "finished");
        sessions.publish(id, "finished", new RunCompleted("完成", List.of(), List.of()));
        tasks.start(id, "后来的任务", List.of(), List.of());
        var detail = service.detail("finished");
        assertEquals("VERIFIED", detail.verification());
        assertEquals(2, detail.research().size());
        assertEquals("复用既有链路", detail.research().getFirst().decision());
        assertFalse(detail.research().get(1).structured());
        assertTrue(service.sessionResearch(id).isEmpty());
    }

    @Test
    void separatesExecutionCompletionFromVerificationAndKeepsHistoricalDetails() {
        String id = sessions.createSession("验证状态").id();
        for (String phase : List.of("CHECKS_PASSED", "CHECKS_FAILED", "CHECKS_STALE", "UNVERIFIED")) {
            tasks.start(id, "修改代码", List.of(), List.of());
            tasks.execution(id, tasks.load(id), new TaskControlService.Execution("DIRECT", "", List.of(), List.of(), 1,
                0, List.of(), phase, "检查详情 " + phase, 0, ""));
            tasks.finish(id);
            tasks.snapshotResult(id, phase);
            sessions.publish(id, phase, new RunCompleted("执行结束", List.of("src/app.js"), List.of()));
            assertEquals("CHECKS_PASSED".equals(phase) ? "COMPLETED" : "NEEDS_ATTENTION", service.get(phase).state());
            assertEquals(phase, service.detail(phase).verification());
            assertEquals("检查详情 " + phase, service.detail(phase).verificationDetail());
        }
        tasks.start(id, "文档修改", List.of(), List.of());
        tasks.execution(id, tasks.load(id), new TaskControlService.Execution("DIRECT", "", List.of(), List.of(), 1, 0,
            List.of(), "UNVERIFIED", "不需要代码测试", 0, ""));
        tasks.finish(id);
        tasks.snapshotResult(id, "docs");
        sessions.publish(id, "docs", new RunCompleted("文档已更新", List.of("README.md"), List.of()));
        assertEquals("COMPLETED", service.get("docs").state(), "无验证的文档交付不必被强制挂起");
        assertEquals("UNVERIFIED", service.detail("docs").verification());
        assertEquals("CHECKS_FAILED", service.detail("CHECKS_FAILED").verification(), "后续任务不能覆盖旧结果的检查状态");
    }

    @Test
    void verificationMetadataDoesNotChangeHistoricalReceiptOrNotificationIdentity() {
        String id = sessions.createSession("旧任务").id();
        sessions.publish(id, "old-run", new RunCompleted("已完成", List.of(), List.of()));
        var before = service.get("old-run");
        service.mark(before.id(), before.revision(), "read");
        service.mark(before.id(), before.revision(), "handle");
        tasks.start(id, "旧任务", List.of(), List.of());
        tasks.execution(id, tasks.load(id),
            new TaskControlService.Execution("GOAL", "", List.of(), List.of(), 1, 0, List.of(), "VERIFIED", "", 0, ""));
        tasks.snapshotResult(id, "old-run");
        var after = service.get("old-run");
        assertEquals(before.revision(), after.revision(), "通知幂等键必须与旧结果保持一致");
        assertTrue(after.read());
        assertTrue(after.handled());
    }
}
