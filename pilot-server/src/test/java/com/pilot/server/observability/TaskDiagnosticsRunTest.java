package com.pilot.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.session.InMemoryRunCheckpointRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.RecoveryReportService;
import com.pilot.server.session.RunStateTracker;
import com.pilot.server.validation.ValidationLedger;

import tools.jackson.databind.json.JsonMapper;

class TaskDiagnosticsRunTest {
    @TempDir
    Path root;

    @Test
    void completedRunSupersedesAnOlderPausedCheckpointAndRetry() {
        Fixture f = fixture();
        f.start("old");
        f.emit("old", new RunRetrying(2, 1000, "旧网络错误"));
        f.tracker.finish(f.sessionId, "old", "PAUSED");
        f.start("new");
        f.emit("new", new RunUsageUpdated(2, 100, 10, 50, 1000, true));
        f.emit("new", new ToolCompleted("call", "新工具结果", 10));
        f.emit("new", new RunCompleted("完成", List.of(), List.of()));
        f.tracker.finish(f.sessionId, "new", "COMPLETED");

        var result = f.diagnostics.diagnose(f.sessionId);

        assertEquals("new", result.activeRunId());
        assertEquals("FINISHED", result.phase());
        assertEquals("本轮已完成", result.lastSuccessfulStep());
        assertEquals("新工具结果", result.lastToolSummary());
        assertNull(result.retryAttempt());
        assertEquals("", result.retryReason());
    }

    @Test
    void newRunDoesNotBorrowUsageOrToolsFromItsPredecessor() {
        Fixture f = fixture();
        f.start("old");
        f.emit("old", new RunUsageUpdated(10, 1000, 100, 50, 1000, true));
        f.emit("old", new ToolCompleted("call", "旧工具结果", 10));
        f.emit("old", new RunRetrying(2, 1000, "旧错误"));
        f.tracker.finish(f.sessionId, "old", "PAUSED");
        f.start("new");

        var result = f.diagnostics.diagnose(f.sessionId);

        assertEquals("new", result.activeRunId());
        assertEquals(0, result.modelRequests());
        assertEquals(0, result.inputTokens());
        assertEquals("", result.lastToolSummary());
        assertNull(result.retryAttempt());
    }

    @Test
    void successfulProgressClearsTheCurrentRetryDisplay() {
        Fixture f = fixture();
        f.start("run");
        f.emit("run", new RunRetrying(2, 1000, "暂时断开"));
        assertEquals(2L, f.diagnostics.diagnose(f.sessionId).retryAttempt());
        f.emit("run", new ToolCompleted("call", "重试后继续执行", 10));
        assertNull(f.diagnostics.diagnose(f.sessionId).retryAttempt());
    }

    @Test
    void latestRunWithoutCheckpointDoesNotShowOlderPausedState() {
        Fixture f = fixture();
        f.start("old");
        f.tracker.finish(f.sessionId, "old", "PAUSED");
        f.sessions.publish(f.sessionId, "new", new RunStarted("新任务"));

        var result = f.diagnostics.diagnose(f.sessionId);

        assertEquals("new", result.activeRunId());
        assertEquals("", result.phase());
        assertEquals("", result.lastSuccessfulStep());
    }

    private Fixture fixture() {
        SessionService sessions = new SessionService();
        String sessionId = sessions.createSession("运行诊断", root.toString()).id();
        RunStateTracker tracker = new RunStateTracker(new InMemoryRunCheckpointRepository(), "test");
        var diagnostics = new TaskDiagnosticsService(sessions, tracker,
            new ValidationLedger(new PilotDataDir(root.resolve("data").toString()), JsonMapper.builder().build()),
            new RecoveryReportService(), new DefaultListableBeanFactory().getBeanProvider(CommandTaskManager.class));
        return new Fixture(sessions, sessionId, tracker, diagnostics);
    }

    private record Fixture(SessionService sessions, String sessionId, RunStateTracker tracker,
        TaskDiagnosticsService diagnostics) {
        void start(String runId) {
            tracker.begin(sessionId, runId, null);
            emit(runId, new RunStarted("测试"));
        }

        void emit(String runId, SessionEvent event) {
            sessions.publish(sessionId, runId, event);
            tracker.onEvent(sessionId, runId, event);
        }
    }
}
