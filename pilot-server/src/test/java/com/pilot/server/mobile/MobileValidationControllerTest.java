package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.session.SessionService;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.validation.ValidationReason;
import com.pilot.server.validation.ValidationStatusService;
import com.pilot.server.validation.WorkspaceFingerprint;

import tools.jackson.databind.json.JsonMapper;

/** 手机端只拿结论：与桌面同一本账，但不带路径和命令正文。 */
class MobileValidationControllerTest {
    @TempDir
    Path root;

    @Test
    void mobileSeesTheSameVerdictAsDesktopWithoutLeakingPaths() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(workspace.resolve("报告.md"), "结论文本");
        SessionService sessions = new SessionService();
        String session = sessions.createSession("手机上核对", workspace.toString()).id();
        PilotDataDir data = new PilotDataDir(root.resolve("data").toString());
        ValidationLedger ledger = new ValidationLedger(data, JsonMapper.builder().build());
        MobileValidationController controller =
            new MobileValidationController(sessions, new ValidationStatusService(ledger));

        // 没有证据：如实说未验证。
        assertEquals("unverified", controller.validation(session).status());
        assertEquals(ValidationReason.NO_EVIDENCE, controller.validation(session).code());

        String candidate = WorkspaceFingerprint.of(workspace, List.of("报告.md"));
        ledger.record(new ValidationEvidence("val_m", session, "run", candidate, List.of("报告.md"),
            List.of("node --test → 通过"), ValidationEvidence.PASSED, "linux|amd64", "run", Instant.now()));

        var verified = controller.validation(session);
        assertEquals("verified", verified.status());
        assertEquals(candidate, verified.candidateHash(), "三端要能对上同一个指纹");
        assertEquals("val_m", verified.evidenceId());

        // 文件变了 → 过期；这是手机端最需要看到的那一档。
        Files.writeString(workspace.resolve("报告.md"), "改过的结论");
        var stale = controller.validation(session);
        assertEquals("stale", stale.status());
        assertEquals(ValidationReason.STALE_CANDIDATE, stale.code());

        // 响应字段里不能出现工作区路径或命令正文。
        String payload = String.join("|", stale.status(), stale.code(), stale.reason(), stale.candidateHash(),
            stale.evidenceId(), stale.recordedAt());
        assertFalse(payload.contains(workspace.toString()), "不能把工作区绝对路径带给手机：" + payload);
        assertFalse(payload.contains("node --test"), "不能把命令正文带给手机：" + payload);
    }

    @Test
    void unknownSessionDegradesInsteadOfThrowing() {
        var view = new MobileValidationController(new SessionService(),
            new ValidationStatusService(
                new ValidationLedger(new PilotDataDir(root.toString()), JsonMapper.builder().build())))
            .validation("no-such-session");
        assertEquals("unverified", view.status());
        assertEquals(ValidationReason.NO_EVIDENCE, view.code());
        assertTrue(view.reason().contains("不存在"));
    }
}
