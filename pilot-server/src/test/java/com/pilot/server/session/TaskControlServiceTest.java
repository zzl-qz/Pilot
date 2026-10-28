package com.pilot.server.session;

import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskControlServiceTest {
    @TempDir Path directory;
    TaskControlService tasks;

    @BeforeEach
    void setup() { tasks = new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(directory.toString())); }

    @Test
    void restartPreservesGoalConstraintsCorrectionsAndEvidence() {
        var first = tasks.start("session", "  保留原文\n修复重连  ", List.of("只能修改客户端"),
                List.of(new TaskControlService.Acceptance("断线重连通过", false, "")));
        tasks.correct("session", "先复现断线后再改");
        tasks.status("session", "PAUSED");
        var reopened = new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(directory.toString()));
        var loaded = reopened.load("session");
        assertEquals(first.originalGoal(), loaded.originalGoal());
        assertEquals(first.taskId(), loaded.taskId());
        assertEquals(List.of("只能修改客户端"), loaded.constraints());
        assertEquals(List.of("先复现断线后再改"), loaded.corrections());
        assertEquals("PAUSED", loaded.status());
        assertTrue(reopened.prompt("session").contains("断线重连通过"));
        assertTrue(reopened.prompt("session").contains("只能修改客户端"));
        assertTrue(reopened.prompt("session").contains("先复现断线后再改"));
    }

    @Test
    void runningCompletionWaitsForEvidenceAndCorrectionReopensVerification() {
        tasks.start("session", "修复重连", List.of(), List.of(new TaskControlService.Acceptance("重连测试通过", false, "")));
        assertEquals("AWAITING_REVIEW", tasks.finish("session").status());
        assertThrows(IllegalArgumentException.class, () -> new TaskControlService.Acceptance("重连测试通过", true, ""));
        var checked = tasks.update("session", List.of("不要改接口"),
                List.of(new TaskControlService.Acceptance("重连测试通过", true, "ReconnectTest.testRetry 通过")));
        assertEquals("COMPLETED", checked.status());
        var corrected = tasks.correct("session", "还要覆盖服务端重启的情形");
        assertEquals("修复重连", corrected.originalGoal());
        assertFalse(corrected.acceptance().getFirst().checked());
        assertEquals("ReconnectTest.testRetry 通过", corrected.acceptance().getFirst().evidence());
        assertEquals("ACTIVE", corrected.status());
    }

    @Test
    void rewindAndForkUseTheSelectedRunsTaskInsteadOfLatestTask() {
        tasks.snapshot("session", "before_first");
        var first = tasks.start("session", "第一件事", List.of("约束 A"), List.of());
        tasks.snapshot("session", "before_second");
        tasks.start("session", "下一件事", List.of("约束 B"), List.of());
        tasks.restoreTo("fork", "before_second");
        assertEquals(first, tasks.load("fork"));
        assertEquals("下一件事", tasks.load("session").originalGoal());
        tasks.restoreTo("session", "before_first");
        assertNull(tasks.load("session"));
        tasks.start("session", "不会继承未来目标", List.of(), List.of());
        tasks.restoreTo("session", "legacy_missing");
        assertNull(tasks.load("session"));
    }

    @Test
    void newTaskDoesNotInheritPreviousConditionsOrCorrections() {
        tasks.start("session", "第一件事", List.of("旧约束"), List.of());
        tasks.correct("session", "旧纠正");
        var next = tasks.start("session", "下一件事", null, null);
        assertEquals("下一件事", next.originalGoal());
        assertTrue(next.constraints().isEmpty());
        assertTrue(next.acceptance().isEmpty());
        assertTrue(next.corrections().isEmpty());
        assertEquals("COMPLETED", tasks.finish("session").status());
    }

    @Test
    void rejectsInvalidTaskStateAndDoesNotReplaceValidRecord() {
        var first = tasks.start("session", "目标", List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> tasks.status("session", "INVALID"));
        assertEquals(first, tasks.load("session"));
        assertThrows(IllegalArgumentException.class, () -> tasks.update("session", List.of(" "), List.of()));
        assertEquals(first, tasks.load("session"));
        assertThrows(IllegalArgumentException.class, () -> tasks.load("../outside"));
    }
}
