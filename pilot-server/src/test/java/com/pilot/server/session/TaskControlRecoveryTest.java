package com.pilot.server.session;

import com.pilot.core.session.SessionService;
import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskControlRecoveryTest {
    @TempDir Path directory;

    @Test
    void restartOnlyInterruptsActiveTasksAndPreservesPausedRequirements() {
        var sessions = new SessionService();
        var mapper = JsonMapper.builder().build();
        var data = new PilotDataDir(directory.toString());
        var original = new TaskControlService(mapper, data);
        Map<String, String> ids = new LinkedHashMap<>();
        for (String status : List.of("ACTIVE", "PAUSED", "AWAITING_REVIEW", "COMPLETED", "FAILED")) {
            String id = sessions.createSession(status).id();
            ids.put(status, id);
            original.start(id, "恢复后仍需满足的原目标", List.of("不能改数据库"),
                    List.of(new TaskControlService.Acceptance("重连测试通过", false, "已有观察记录")));
            original.correct(id, "优先覆盖断线再连接");
            original.status(id, status);
        }
        String blank = sessions.createSession("没有任务记录").id();
        var restarted = new TaskControlService(mapper, data);
        var recovery = new TaskControlRecovery(sessions, restarted);
        recovery.run(null);
        for (var entry : ids.entrySet()) {
            var task = restarted.load(entry.getValue());
            assertEquals(entry.getKey().equals("ACTIVE") ? "INTERRUPTED" : entry.getKey(), task.status());
            assertEquals("恢复后仍需满足的原目标", task.originalGoal());
            assertEquals(List.of("不能改数据库"), task.constraints());
            assertEquals(List.of("优先覆盖断线再连接"), task.corrections());
            assertEquals("重连测试通过", task.acceptance().getFirst().text());
            assertEquals("已有观察记录", task.acceptance().getFirst().evidence());
        }
        assertNull(restarted.load(blank));
        recovery.run(null);
        assertEquals("PAUSED", restarted.load(ids.get("PAUSED")).status());
        assertEquals("INTERRUPTED", restarted.load(ids.get("ACTIVE")).status());
    }
}
