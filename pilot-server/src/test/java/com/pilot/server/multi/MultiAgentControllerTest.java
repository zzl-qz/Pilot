package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.AgentProperties;

import tools.jackson.databind.json.JsonMapper;

/** 接受变更是唯一写真实工作区的入口：校验与仲裁门控必须在服务端生效，绕过界面也拦得住。 */
class MultiAgentControllerTest {
    @TempDir
    Path workspace;
    @TempDir
    Path data;

    private SandboxStore store;
    private MultiAgentController controller;
    private VerificationGate.Report nextReport;
    private Runnable duringVerification = () -> {
    };

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
        AgentProperties properties =
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 2, 1, 60_000, true);
        VerificationGate gate = (root, base, changes) -> {
            duringVerification.run();
            return nextReport;
        };
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

        ResponseStatusException error =
            assertThrows(ResponseStatusException.class, () -> controller.accept("s1", "sbx_1", null));

        assertEquals(412, error.getStatusCode().value());
        assertTrue(error.getReason().contains("硬校验未通过"), error.getReason());
        assertFalse(Files.exists(workspace.resolve("a.txt")), "未通过校验的改动不能写入工作区");
    }

    @Test
    void unverifiedChangeNeedsExplicitAcknowledgement() {
        nextReport = VerificationGate.Report.skipped("没有识别到可用的构建命令");

        assertThrows(ResponseStatusException.class, () -> controller.accept("s1", "sbx_1", null));
        SandboxStore.ApplyReport accepted =
            controller.accept("s1", "sbx_1", new MultiAgentController.AcceptRequest(true));

        assertTrue(accepted.ok(), accepted.message());
    }

    @Test
    void unresolvedArbitrationBlocksAccepting() {
        nextReport = VerificationGate.Report.passed(List.of("ok"));
        store.raise("s1", new SandboxStore.Arbitration("arb_1", "conflict", "t1", List.of("重新生成", "人工修改"), "冲突待决"));

        ResponseStatusException error =
            assertThrows(ResponseStatusException.class, () -> controller.accept("s1", "sbx_1", null));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("arb_1"), error.getReason());
    }

    @Test
    void resolvingPersistsDecisionAndDoesNotApplyFilesOrReplayWork() {
        store.raise("s1", new SandboxStore.Arbitration("arb", "conflict", "t1", List.of("重新生成", "人工修改"), "choose"));
        var request = new MultiAgentController.ResolveRequest("人工修改");
        assertEquals(204, controller.resolve("s1", "arb", request).getStatusCode().value());
        assertEquals(204, controller.resolve("s1", "arb", request).getStatusCode().value());
        assertTrue(store.arbitrations("s1").isEmpty());
        assertFalse(Files.exists(workspace.resolve("a.txt")), "仲裁回执不能自动写文件");
        var conflict = assertThrows(ResponseStatusException.class,
            () -> controller.resolve("s1", "arb", new MultiAgentController.ResolveRequest("重新生成")));
        assertEquals(409, conflict.getStatusCode().value());
        var missing = assertThrows(ResponseStatusException.class, () -> controller.resolve("s1", "missing", request));
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    void staleReviewAndMutationDuringVerificationCannotApplyOrRecordPassingResult() {
        nextReport = VerificationGate.Report.passed(List.of("ok"));
        String reviewed = controller.manifest("s1", "sbx_1").reviewHash();
        duringVerification =
            () -> store.find("s1", "sbx_1").orElseThrow().add("a.txt", null, "changed candidate", false, RiskLevel.LOW);
        var error = assertThrows(ResponseStatusException.class,
            () -> controller.accept("s1", "sbx_1", new MultiAgentController.AcceptRequest(false, reviewed)));
        assertEquals(409, error.getStatusCode().value());
        assertFalse(Files.exists(workspace.resolve("a.txt")), "版本变化不能写入");
        Assertions.assertNull(store.verification("s1", "sbx_1"));
        duringVerification = () -> {
            throw new AssertionError("旧版本应在运行校验前拒绝");
        };
        assertEquals(409,
            assertThrows(ResponseStatusException.class,
                () -> controller.accept("s1", "sbx_1", new MultiAgentController.AcceptRequest(true, reviewed)))
                .getStatusCode().value());
    }

    @Test
    void duplicateAcceptAndRecoveryAreIdempotent() {
        nextReport = VerificationGate.Report.passed(List.of("ok"));
        assertTrue(controller.accept("s1", "sbx_1", null).ok());
        duringVerification = () -> {
            throw new AssertionError("不能重复校验已接受的内容");
        };
        assertTrue(controller.accept("s1", "sbx_1", null).written().isEmpty());
        assertEquals(204, controller.recover("s1", "sbx_1").getStatusCode().value());
        assertEquals(1, store.workspaceRevision("s1"));
        assertEquals("accepted", controller.manifest("s1", "sbx_1").disposition());
    }

    private static void assertFalse(boolean condition, String message) {
        Assertions.assertFalse(condition, message);
    }
}
