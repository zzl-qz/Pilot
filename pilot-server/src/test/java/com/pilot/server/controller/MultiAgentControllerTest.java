package com.pilot.server.controller;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.multi.VerificationGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 接受变更是唯一写真实工作区的入口：校验与仲裁门控必须在服务端生效，绕过界面也拦得住。 */
class MultiAgentControllerTest {
    @TempDir Path workspace;
    @TempDir Path data;

    private SandboxStore store;
    private MultiAgentController controller;
    private VerificationGate.Report nextReport;

    @BeforeEach
    void setUp() {
        store = new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(data.toString()));
        DiffSandbox sandbox = new DiffSandbox("sbx_1", "agent_1", "t1", "");
        sandbox.add("a.txt", null, "new content", false, RiskLevel.LOW);
        store.add("s1", sandbox);

        SessionService sessions = mock(SessionService.class);
        Session session = mock(Session.class);
        when(session.workspace()).thenReturn(workspace.toString());
        when(sessions.find("s1")).thenReturn(Optional.of(session));
        AgentProperties properties = new AgentProperties(workspace.toString(), PermissionMode.AUTO,
                8, 3, 5, 300, 2, 1, 60_000, true);
        VerificationGate gate = (root, base, changes) -> nextReport;
        controller = new MultiAgentController(sessions, store, properties, gate);
    }

    @Test
    void passingVerificationAllowsWritingToWorkspace() throws Exception {
        nextReport = VerificationGate.Report.passed(List.of("mvn -q test → exit 0"));

        SandboxStore.ApplyReport report = controller.accept("s1", "sbx_1", null);

        assertTrue(report.ok(), report.message());
        assertEquals("new content", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void failedVerificationBlocksAccepting() throws Exception {
        nextReport = VerificationGate.Report.failed(List.of("mvn -q test → exit 1"), "编译与测试未通过");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.accept("s1", "sbx_1", null));

        assertEquals(412, error.getStatusCode().value());
        assertTrue(error.getReason().contains("硬校验未通过"), error.getReason());
        assertFalse(Files.exists(workspace.resolve("a.txt")), "未通过校验的改动不能写入工作区");
    }

    @Test
    void unverifiedChangeNeedsExplicitAcknowledgement() {
        nextReport = VerificationGate.Report.skipped("没有识别到可用的构建命令");

        assertThrows(ResponseStatusException.class, () -> controller.accept("s1", "sbx_1", null));
        SandboxStore.ApplyReport accepted = controller.accept("s1", "sbx_1",
                new MultiAgentController.AcceptRequest(true));

        assertTrue(accepted.ok(), accepted.message());
    }

    @Test
    void unresolvedArbitrationBlocksAccepting() {
        nextReport = VerificationGate.Report.passed(List.of("ok"));
        store.raise("s1", new SandboxStore.Arbitration("arb_1", "conflict", "t1",
                List.of("重新生成", "人工修改"), "冲突待决"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.accept("s1", "sbx_1", null));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("arb_1"), error.getReason());
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}
