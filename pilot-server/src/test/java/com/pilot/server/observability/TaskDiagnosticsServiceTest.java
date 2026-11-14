package com.pilot.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.pilot.core.session.InMemoryRunCheckpointRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.session.RecoveryReportService;
import com.pilot.server.session.RunStateTracker;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.validation.WorkspaceFingerprint;

import tools.jackson.databind.json.JsonMapper;

class TaskDiagnosticsServiceTest {
    @TempDir
    Path root;

    private Path workspace;
    private String sessionId;
    private ValidationLedger ledger;
    private TaskDiagnosticsService diagnostics;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(root.resolve("工作区 with spaces"));
        SessionService sessions = new SessionService();
        sessionId = sessions.createSession("校验证据诊断", workspace.toString()).id();
        ledger = new ValidationLedger(new PilotDataDir(root.resolve("data").toString()), JsonMapper.builder().build());
        diagnostics = new TaskDiagnosticsService(sessions,
            new RunStateTracker(new InMemoryRunCheckpointRepository(), "test-instance"), ledger,
            new RecoveryReportService(), new DefaultListableBeanFactory().getBeanProvider(CommandTaskManager.class));
    }

    @ParameterizedTest
    @CsvSource({"passed, failed, false", "failed, passed, true", "passed, skipped, false"})
    void latestEvidenceControlsStatusAndFingerprintWhenTheFileSetChanges(String oldStatus, String latestStatus,
        boolean covered) throws Exception {
        Files.writeString(workspace.resolve("old.js"), "old content");
        Files.writeString(workspace.resolve("new.js"), "new content");
        record("old", "old.js", oldStatus, Instant.EPOCH);
        ValidationEvidence latest = record("latest", "new.js", latestStatus, Instant.EPOCH.plusSeconds(1));

        TaskDiagnosticsService.ValidationView view = diagnostics.diagnose(sessionId).validation();

        assertEquals(covered, view.covered());
        assertEquals(latestStatus, view.status());
        assertEquals(latest.candidateHash(), view.candidateHash(), "当前指纹必须使用最新证据的文件集合");
        assertEquals(latest.checks(), view.checks());
        assertEquals(latest.recordedAt(), view.recordedAt());
        assertEquals(latest.runId(), view.runId());
        assertEquals(covered ? "" : "最近一次校验没有通过（" + latestStatus + "）", view.reason());
    }

    @Test
    void changingTheLatestArtifactInvalidatesItsPassingEvidence() throws Exception {
        Files.writeString(workspace.resolve("old.js"), "old content");
        Files.writeString(workspace.resolve("new.js"), "tested content");
        record("old", "old.js", "failed", Instant.EPOCH);
        record("latest", "new.js", "passed", Instant.EPOCH.plusSeconds(1));
        Files.writeString(workspace.resolve("new.js"), "changed after verification");

        TaskDiagnosticsService.ValidationView view = diagnostics.diagnose(sessionId).validation();

        assertFalse(view.covered());
        assertEquals("passed", view.status(), "记录的结果与它是否仍覆盖当前产物分别展示");
        assertEquals("run_latest", view.runId());
        assertEquals(WorkspaceFingerprint.of(workspace, List.of("new.js")), view.candidateHash());
        assertTrue(view.reason().contains("代码已变化"), view.reason());
    }

    @Test
    void emptyLedgerReportsNoEvidenceWithoutInventingAPassingStatus() {
        TaskDiagnosticsService.ValidationView view = diagnostics.diagnose(sessionId).validation();

        assertFalse(view.covered());
        assertEquals("还没有任何校验证据", view.reason());
        assertEquals("", view.status());
        assertEquals("", view.candidateHash());
        assertEquals(List.of(), view.checks());
        assertNull(view.recordedAt());
        assertEquals("", view.runId());
    }

    private ValidationEvidence record(String id, String file, String status, Instant at) {
        ValidationEvidence evidence = new ValidationEvidence("val_" + id, sessionId, "run_" + id,
            WorkspaceFingerprint.of(workspace, List.of(file)), List.of(file),
            "skipped".equals(status) ? List.of()
                : List.of("npm test -- " + file + " → exit " + ("passed".equals(status) ? "0" : "1")),
            status, "test-environment", "run", at);
        ledger.record(evidence);
        return evidence;
    }
}
