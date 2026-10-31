package com.pilot.server.multi;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxStoreTest {

    private static final String ORIGINAL = "a\nb\nc\n";

    @TempDir
    Path workspace;

    @TempDir
    Path dataDir;

    private SandboxStore store() {
        return new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString()));
    }

    private DiffSandbox proposal() throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/A.java"), ORIGINAL);
        DiffSandbox sandbox = new DiffSandbox("sbx_1", "agent_1", "T1", "");
        sandbox.add("src/A.java", ORIGINAL, "a\nCHANGED\nc\n", true, RiskLevel.MEDIUM);
        return sandbox;
    }

    @Test
    void acceptWritesSandboxContentToWorkspace() throws IOException {
        SandboxStore store = store();
        store.add("ses_1", proposal());
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("src/A.java")), "接受之前不能落盘");

        SandboxStore.ApplyReport report = store.accept("ses_1", "sbx_1", workspace);

        assertTrue(report.ok(), report.message());
        assertEquals("a\nCHANGED\nc\n", Files.readString(workspace.resolve("src/A.java")));
        assertEquals(1, store.diffs("ses_1").size());
    }

    @Test
    void acceptRefusesToOverwriteAFileThatChangedMeanwhile() throws IOException {
        SandboxStore store = store();
        store.add("ses_1", proposal());
        Files.writeString(workspace.resolve("src/A.java"), "a\n用户改过了\nc\n");

        SandboxStore.ApplyReport report = store.accept("ses_1", "sbx_1", workspace);

        assertFalse(report.ok());
        assertTrue(report.failed().get(0).contains("已被修改"), report.failed().toString());
        assertEquals("a\n用户改过了\nc\n", Files.readString(workspace.resolve("src/A.java")));
    }

    @Test
    void rejectLeavesWorkspaceUntouched() throws IOException {
        SandboxStore store = store();
        store.add("ses_1", proposal());

        assertTrue(store.reject("ses_1", "sbx_1"));
        store.accept("ses_1", "sbx_1", workspace);

        assertEquals(ORIGINAL, Files.readString(workspace.resolve("src/A.java")));
        assertTrue(store.diffs("ses_1").get(0).status().equals("rejected"));
    }

    @Test
    void refusesToWriteOutsideWorkspace() throws IOException {
        SandboxStore store = store();
        DiffSandbox sandbox = new DiffSandbox("sbx_2", "agent_2", "T2", "");
        sandbox.add("../../escape.txt", null, "boom", false, RiskLevel.MEDIUM);
        store.add("ses_1", sandbox);

        SandboxStore.ApplyReport report = store.accept("ses_1", "sbx_2", workspace);

        assertFalse(report.ok());
        assertFalse(Files.exists(workspace.getParent().resolve("escape.txt")));
    }

    @Test
    void restoresSandboxAndVerificationAfterStoreRecreation() throws IOException {
        SandboxStore first = store();
        DiffSandbox sandbox = proposal();
        first.add("ses_restart", sandbox);
        first.recordTasks("ses_restart", java.util.List.of(new SandboxStore.TaskView(
                "T1", "任务一", "agent_1", "DONE", "产物完成", 1)));
        first.raise("ses_restart", new SandboxStore.Arbitration(
                "arb_1", "dissent", "T1", java.util.List.of("方案 A"), "需要人工确认"));
        first.recordVerification("ses_restart", "sbx_1",
                VerificationGate.Report.passed(java.util.List.of("mvn test")));

        SandboxStore restored = store();

        assertEquals(1, restored.sandboxes("ses_restart").size());
        assertEquals("a\nCHANGED\nc\n",
                restored.find("ses_restart", "sbx_1").orElseThrow().changes().get(0).newContent());
        assertTrue(restored.verification("ses_restart", "sbx_1").ok());
        assertEquals("DONE", restored.tasks("ses_restart").get(0).status());
        assertEquals("arb_1", restored.arbitrations("ses_restart").get(0).id());
    }

    @Test
    void verificationIsInvalidatedWhenCandidateChanges() throws IOException {
        SandboxStore store = store();
        DiffSandbox sandbox = proposal();
        store.add("ses_candidate", sandbox);
        store.recordVerification("ses_candidate", "sbx_1",
                VerificationGate.Report.passed(java.util.List.of("test")));
        assertTrue(store.verification("ses_candidate", "sbx_1").ok());

        sandbox.add("src/A.java", "a\nCHANGED\nc\n", "a\nNEWER\nc\n", true, RiskLevel.MEDIUM);
        store.add("ses_candidate", sandbox);

        assertTrue(store.verification("ses_candidate", "sbx_1") == null,
                "旧候选的通过结果不能用于新候选");
    }
}
