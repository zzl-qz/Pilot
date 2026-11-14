package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.server.common.data.PilotDataDir;

import tools.jackson.databind.json.JsonMapper;

class SandboxArtifactTest {
    private static final String ORIGINAL = "a\nb\n";
    private static final String ENV = "linux|amd64|java25";

    @TempDir
    Path dataDir;

    private SandboxStore store() {
        return new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString()));
    }

    private static DiffSandbox sandbox(String id, String taskId, String content) {
        DiffSandbox sandbox = new DiffSandbox(id, "agent_1", taskId, "base123");
        sandbox.add("src/A.java", ORIGINAL, content, true, RiskLevel.LOW);
        return sandbox;
    }

    private static VerificationGate.Report passed() {
        return VerificationGate.Report.passed(List.of("mvn -q test → exit 0"));
    }

    @Test
    void verificationIsBoundToTheCandidateManifest() {
        SandboxStore store = store();
        DiffSandbox sandbox = sandbox("sbx_1", "T1", "a\nCHANGED\n");
        store.add("ses_1", sandbox);

        store.recordVerification("ses_1", "sbx_1", ENV, passed());
        assertTrue(store.verificationState("ses_1", "sbx_1").ok());

        // 内容再改一版：旧的「通过」不再覆盖当前产物
        sandbox.add("src/A.java", "a\nCHANGED\n", "a\nCHANGED-AGAIN\n", true, RiskLevel.LOW);
        SandboxStore.VerificationState state = store.verificationState("ses_1", "sbx_1");

        assertFalse(state.current());
        assertTrue(state.reason().contains("候选内容"), state.reason());
        assertTrue(store.verification("ses_1", "sbx_1") == null, "失效的校验结果不能当成通过");
    }

    @Test
    void manifestHistoryAndDiffShowRevisions() {
        SandboxStore store = store();
        DiffSandbox sandbox = sandbox("sbx_1", "T1", "a\none\n");
        store.add("ses_1", sandbox);

        store.recordVerification("ses_1", "sbx_1", ENV, passed());
        sandbox.add("src/A.java", "a\none\n", "a\ntwo\n", true, RiskLevel.LOW);
        store.recordVerification("ses_1", "sbx_1", ENV, VerificationGate.Report.passed(List.of("mvn test → exit 0")));

        var history = store.manifestHistory("ses_1", "sbx_1");
        assertEquals(2, history.size(), "每次内容变化产生一个新修订: " + history);
        assertEquals(2, history.getLast().revision());

        var diff = store.manifestDiff("ses_1", "sbx_1");
        assertTrue(diff.changed());
        assertTrue(diff.modified().contains("src/A.java"), diff.toString());
        assertFalse(diff.currentCandidate().isBlank());
    }

    @Test
    void upstreamChangeInvalidatesDownstreamVerification() {
        SandboxStore store = store();
        DiffSandbox upstream = sandbox("sbx_up", "T1", "a\nup-1\n");
        DiffSandbox downstream = sandbox("sbx_down", "T2", "a\ndown-1\n");
        store.add("ses_1", upstream);
        store.add("ses_1", downstream);
        store.linkDependency("ses_1", "sbx_up", "sbx_down");
        store.linkTaskDependencies("ses_1", List.of("T1"), "sbx_down");

        store.recordVerification("ses_1", "sbx_up", ENV, passed());
        store.recordVerification("ses_1", "sbx_down", ENV, passed());
        assertTrue(store.verificationState("ses_1", "sbx_down").ok());

        // 上游产物迭代后，下游必须重新校验
        upstream.add("src/A.java", "a\nup-1\n", "a\nup-2\n", true, RiskLevel.LOW);
        store.recordVerification("ses_1", "sbx_up", ENV, passed());

        SandboxStore.VerificationState state = store.verificationState("ses_1", "sbx_down");
        assertFalse(state.current());
        assertTrue(state.reason().contains("上游依赖"), state.reason());
        assertEquals(List.of("sbx_up"), store.dependenciesOf("ses_1", "sbx_down"));
    }
}
