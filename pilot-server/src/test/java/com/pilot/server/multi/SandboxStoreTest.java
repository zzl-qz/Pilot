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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void acceptingOneSandboxInvalidatesVerificationsOfTheOthers() throws IOException {
        SandboxStore store = store();
        store.add("ses_partial", proposal());
        DiffSandbox other = new DiffSandbox("sbx_2", "agent_2", "T2", "");
        other.add("src/B.java", null, "class B {}\n", false, RiskLevel.MEDIUM);
        store.add("ses_partial", other);
        store.recordVerification("ses_partial", "sbx_1",
                VerificationGate.Report.passed(java.util.List.of("mvn test")));
        store.recordVerification("ses_partial", "sbx_2",
                VerificationGate.Report.passed(java.util.List.of("mvn test")));

        assertTrue(store.accept("ses_partial", "sbx_1", workspace).ok());
        assertEquals(1, store.workspaceRevision("ses_partial"), "写入与校验失效必须由接受入口一起记账");

        SandboxStore.VerificationState state = store.verificationState("ses_partial", "sbx_2");
        assertFalse(state.current(), "工作区已被改写，其余沙箱的旧结论不能继续放行");
        assertTrue(state.reason().contains("重新校验"), state.reason());
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

    @Test
    void resetRemovesPersistedSessionAndRepeatedRestoreDoesNotDuplicateSandbox() throws IOException {
        SandboxStore first = store();
        first.add("ses_cleanup", proposal());

        SandboxStore restored = store();
        assertEquals(1, restored.sandboxes("ses_cleanup").size());
        assertEquals(1, restored.sandboxes("ses_cleanup").size());

        restored.reset("ses_cleanup");

        assertEquals(0, restored.sandboxes("ses_cleanup").size());
        assertEquals(0, Files.list(dataDir.resolve("multi")).toList().size());
    }

    @Test void addingBeforeFirstReadRestoresOldArtifactsAndDeduplicatesIds() throws Exception {
        store().add("ses", proposal());
        var second = store(); var next = new DiffSandbox("sbx_2", "agent_2", "T2", "");
        second.add("ses", next); second.add("ses", next);
        assertEquals(2, second.sandboxes("ses").size());
        assertEquals(2, store().sandboxes("ses").size());
    }

    @Test void restartCancelsUnownedExecutionButKeepsTerminalTaskStates() {
        var first = store();
        first.recordTasks("ses", java.util.List.of(
                new SandboxStore.TaskView("t1", "one", "a", "running", "executing", 1),
                new SandboxStore.TaskView("t2", "two", "a", "READY", "ready", 0),
                new SandboxStore.TaskView("t3", "three", "a", "done", "done", 1)));
        var restored = store().tasks("ses");
        assertEquals(java.util.List.of("cancelled", "cancelled", "done"), restored.stream().map(SandboxStore.TaskView::status).toList());
        assertTrue(restored.getFirst().detail().contains("重新派发"));
        assertEquals(restored, store().tasks("ses"));
    }

    @Test void runningCheckpointsAreNotAcceptableUntilRecoveredAndRevalidated() throws Exception {
        var first = store(); first.checkpoint("ses", proposal());
        assertTrue(first.sandboxes("ses").isEmpty());
        assertFalse(first.accept("ses", "sbx_1", workspace).ok());
        var restored = store(); assertEquals(1, restored.sandboxes("ses").size());
        assertFalse(restored.verificationState("ses", "sbx_1").ok());
        restored.add("ses", restored.find("ses", "sbx_1").orElseThrow());
        assertFalse(Files.exists(dataDir.resolve("multi/ses/checkpoints/sbx_1.json")));
        assertEquals(1, store().sandboxes("ses").size());
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("src/A.java")));
    }

    @Test void arbitrationDecisionsSurviveRestartAndConflictingReceiptsAreRejected() {
        var item = new SandboxStore.Arbitration("arb", "conflict", "T1", java.util.List.of("A", "B"), "choose");
        var first = store(); first.raise("ses", item); first.raise("ses", item);
        assertEquals(1, first.arbitrations("ses").size());
        assertThrows(IllegalArgumentException.class, () -> first.resolve("ses", "arb", "invalid"));
        assertThrows(java.util.NoSuchElementException.class, () -> first.resolve("ses", "missing", "A"));
        assertTrue(first.resolve("ses", "arb", "A"));
        var restored = store(); assertTrue(restored.arbitrations("ses").isEmpty());
        assertFalse(restored.resolve("ses", "arb", "A"));
        assertThrows(IllegalStateException.class, () -> restored.resolve("ses", "arb", "B"));
        restored.raise("ses", new SandboxStore.Arbitration("arb2", "verification", "T2", java.util.List.of("retry"), "failed"));
        assertEquals("arb2", store().arbitrations("ses").getFirst().id());
    }

    @Test void corruptedStateFailsClosedAndCanBeRetriedAfterRepair() throws Exception {
        var first = store(); first.add("ses", proposal());
        Path file = dataDir.resolve("multi/ses/arbitrations.json"); Files.writeString(file, "{broken");
        var restored = store(); assertThrows(IllegalStateException.class, () -> restored.sandboxes("ses"));
        assertThrows(IllegalStateException.class, () -> restored.arbitrations("ses"));
        Files.writeString(file, "null");
        assertThrows(IllegalStateException.class, () -> restored.arbitrations("ses"), "合法 JSON 的空值也不能被当作没有待决仲裁");
        Files.writeString(file, "[]"); assertEquals(1, restored.sandboxes("ses").size());
    }

    @Test void rejectsPathTraversalAndReportsPersistenceFailure() throws Exception {
        var first = store();
        for (String id : java.util.List.of("..", "../outside", "a/b", "a\\b", ""))
            assertThrows(IllegalArgumentException.class, () -> first.sandboxes(id));
        Files.writeString(dataDir.resolve("multi"), "not a directory");
        assertThrows(java.io.UncheckedIOException.class, () -> first.add("ses", proposal()));
        assertTrue(first.sandboxes("ses").isEmpty(), "写入失败不能被报告为已保存的产物");
    }

    @Test void concurrentFirstReadsNeverObservePartialRestoreOrDuplicates() throws Exception {
        var first = store(); first.add("ses", proposal());
        first.raise("ses", new SandboxStore.Arbitration("arb", "conflict", "t", java.util.List.of("A"), "choose"));
        var restored = store();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 30; i++) futures.add(executor.submit(() -> {
                assertEquals(1, restored.sandboxes("ses").size()); assertEquals(1, restored.arbitrations("ses").size());
            }));
            for (var future : futures) future.get();
        }
    }

    @Test void conflictsPreventTheEntireBatchFromBeingApplied() throws Exception {
        var first = store(); var box = proposal();
        box.add("conflict", null, "proposed", false, RiskLevel.LOW);
        Files.writeString(workspace.resolve("conflict"), "user content"); first.add("ses", box);
        var report = first.accept("ses", "sbx_1", workspace);
        assertFalse(report.ok()); assertTrue(report.written().isEmpty());
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("src/A.java")));
        assertEquals(0, store().workspaceRevision("ses"));
        assertTrue(store().diffs("ses").stream().allMatch(diff -> diff.status().equals("pending")));
    }
}
