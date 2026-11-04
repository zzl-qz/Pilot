package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.session.SessionService;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.RunStarter;
import com.pilot.server.session.TaskControlService;

import tools.jackson.databind.json.JsonMapper;

class TaskControlWakeTest {
    @TempDir
    Path data;

    @Test
    void releasesEditSlotBeforeWakingQueueAndReturnsCurrentTask() {
        SessionService sessions = new SessionService();
        String id = sessions.createSession("验收测试").id();
        TaskControlService tasks =
            new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(data.toString()));
        tasks.start(id, "原任务", List.of(), List.of(new TaskControlService.Acceptance("测试通过", false, "")));
        tasks.finish(id);
        RecordingRuns runs = new RecordingRuns(() -> {
            assertEquals("COMPLETED", tasks.load(id).status());
            tasks.start(id, "排队的下一件事", List.of(), List.of());
        });
        TaskControlController controller = new TaskControlController(sessions, runs, tasks);
        TaskControlService.TaskContract response = controller.update(id, new TaskControlService.Requirements(List.of(),
            List.of(new TaskControlService.Acceptance("测试通过", true, "实际测试输出通过"))));
        assertEquals(1, runs.notifications);
        assertFalse(runs.claimed);
        assertEquals("排队的下一件事", response.originalGoal());
        assertEquals("ACTIVE", response.status());
    }

    @Test
    void editingPausedOrUnverifiedTaskNeverStartsNextTask() {
        SessionService sessions = new SessionService();
        String id = sessions.createSession("验收测试").id();
        TaskControlService tasks =
            new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(data.toString()));
        RecordingRuns runs = new RecordingRuns(() -> fail("尚未完成验收不能启动下一件事"));
        TaskControlController controller = new TaskControlController(sessions, runs, tasks);
        tasks.start(id, "原任务", List.of(), List.of(new TaskControlService.Acceptance("测试通过", false, "")));
        tasks.finish(id);
        controller.update(id, new TaskControlService.Requirements(List.of("保留接口"),
            List.of(new TaskControlService.Acceptance("测试通过", false, "待验证"))));
        assertEquals(0, runs.notifications);
        tasks.status(id, "PAUSED");
        controller.update(id, new TaskControlService.Requirements(List.of(),
            List.of(new TaskControlService.Acceptance("测试通过", true, "已保存的测试证据"))));
        assertEquals("PAUSED", tasks.load(id).status());
        assertEquals(0, runs.notifications);
        assertFalse(runs.claimed);
    }

    private static final class RecordingRuns implements RunStarter {
        boolean claimed;
        int notifications;
        final Runnable onNotification;

        RecordingRuns(Runnable onNotification) {
            this.onNotification = onNotification;
        }

        public void start(String sessionId, String runId, String input) {
        }

        public Optional<String> claimRun(String sessionId) {
            assertFalse(claimed);
            claimed = true;
            return Optional.of("edit-slot");
        }

        public void abandon(String runId) {
            assertEquals("edit-slot", runId);
            claimed = false;
        }

        public void requirementsUpdated(String sessionId) {
            assertFalse(claimed, "队列启动前必须释放编辑占位");
            notifications++;
            onNotification.run();
        }
    }
}
