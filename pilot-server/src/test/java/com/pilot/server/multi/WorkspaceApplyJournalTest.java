package com.pilot.server.multi;

import com.pilot.core.multi.*;
import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceApplyJournalTest {
    @TempDir Path root;
    @TempDir Path data;
    private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build();
    private SandboxStore store(WorkspaceApplyJournal.FaultPoint faults) {
        return new SandboxStore(mapper, new PilotDataDir(data.toString()), new WorkspaceApplyJournal(data.resolve("apply-journal"), mapper, faults));
    }
    private DiffSandbox candidate() throws Exception {
        Files.writeString(root.resolve("a"), "before");
        var box = new DiffSandbox("sbx", "agent", "task", "");
        box.add("a", "before", "after", true, RiskLevel.LOW);
        box.add("new", null, "created", false, RiskLevel.LOW); return box;
    }
    @Test void preflightConflictDoesNotWriteAnyFile() throws Exception {
        var store = store((p, i) -> {}); store.add("s", candidate()); Files.writeString(root.resolve("new"), "user");
        assertFalse(store.accept("s", "sbx", root).ok());
        assertEquals("before", Files.readString(root.resolve("a"))); assertEquals("user", Files.readString(root.resolve("new")));
        assertNull(store.applyState(root)); assertEquals(0, store.workspaceRevision("s"));
    }
    @Test void writeFailureRestoresExistingAndNewFilesAndAllowsRetry() throws Exception {
        var store = store((point, i) -> { if (point.equals("after-write") && i == 1) throw new IOException("模拟磁盘故障"); });
        store.add("s", candidate());
        assertFalse(store.accept("s", "sbx", root).ok());
        assertEquals("before", Files.readString(root.resolve("a"))); assertFalse(Files.exists(root.resolve("new")));
        assertNull(store.applyState(root));
        var restarted = store((p, i) -> {}); assertTrue(restarted.accept("s", "sbx", root).ok());
        assertEquals("after", Files.readString(root.resolve("a")));
        assertTrue(restarted.accept("s", "sbx", root).written().isEmpty()); assertEquals(1, restarted.workspaceRevision("s"));
    }
    @Test void recoveryNeverOverwritesChangesMadeAfterTheInterruptedOperation() throws Exception {
        var store = store((point, i) -> {
            if (point.equals("after-write") && i == 0) { Files.writeString(root.resolve("a"), "user edit"); throw new IOException("失败"); }
        });
        store.add("s", candidate()); assertFalse(store.accept("s", "sbx", root).ok());
        assertNotNull(store.applyState(root));
        var restarted = store((p, i) -> {});
        assertThrows(java.io.UncheckedIOException.class, () -> restarted.recoverApply("s", "sbx", root));
        assertEquals("user edit", Files.readString(root.resolve("a"))); assertFalse(Files.exists(root.resolve("new")));
        Files.writeString(root.resolve("a"), "after"); restarted.recoverApply("s", "sbx", root);
        assertEquals("before", Files.readString(root.resolve("a"))); assertNull(restarted.applyState(root));
    }
    @Test void processExitIsRecoverableBeforeAndAfterJournalCommit() throws Exception {
        for (String phase : List.of("prepared", "after-write", "before-commit", "after-commit", "after-revision", "after-metadata")) {
            Path workspace = Files.createDirectory(root.resolve(phase)), dir = Files.createDirectory(data.resolve(phase));
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    WorkspaceApplyCrashMain.class.getName(), workspace.toString(), dir.toString(), phase).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
                assertEquals(17, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            } finally { if (process.isAlive()) process.destroyForcibly(); }
            var restored = new SandboxStore(mapper, new PilotDataDir(dir.toString()));
            assertNotNull(restored.applyState(workspace));
            assertThrows(IllegalStateException.class, () -> restored.reset("s"));
            restored.recoverApply("s", "sbx", workspace);
            assertNull(restored.applyState(workspace));
            assertEquals(Set.of("after-commit", "after-revision", "after-metadata").contains(phase) ? "after" : "before", Files.readString(workspace.resolve("a")));
            assertEquals(Set.of("after-commit", "after-revision", "after-metadata").contains(phase), Files.exists(workspace.resolve("new")));
            assertTrue(restored.accept("s", "sbx", workspace).ok());
            assertEquals(1, restored.workspaceRevision("s"));
        }
    }
    @Test void concurrentAcceptsSerializeAndApplyOnlyOnce() throws Exception {
        var store = store((p, i) -> {}); store.add("s", candidate());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SandboxStore.ApplyReport>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> store.accept("s", "sbx", root)));
            int writes = 0;
            for (var future : results) { var result = future.get(10, TimeUnit.SECONDS); assertTrue(result.ok()); writes += result.written().size(); }
            assertEquals(2, writes); assertEquals(1, store.workspaceRevision("s"));
        }
    }
    @Test void invalidVersionAndSymlinkTargetsCannotBeAccepted() throws Exception {
        var store = store((p, i) -> {}); store.add("s", candidate());
        assertThrows(IllegalStateException.class, () -> store.accept("s", "sbx", root, "old-version"));
        Files.delete(root.resolve("a"));
        try { Files.createSymbolicLink(root.resolve("a"), data.resolve("secret")); }
        catch (UnsupportedOperationException | FileSystemException e) { org.junit.jupiter.api.Assumptions.abort("无符号链接权限"); }
        Files.writeString(data.resolve("secret"), "before");
        assertFalse(store.accept("s", "sbx", root).ok()); assertEquals("before", Files.readString(data.resolve("secret")));
    }
    @Test void changeImmediatelyBeforeWriteIsPreservedAndCorruptJournalFailsClosed() throws Exception {
        var interrupted = store((point, index) -> {
            if (point.equals("before-write")) Files.writeString(root.resolve("a"), "latest user edit");
        });
        interrupted.add("s", candidate()); assertFalse(interrupted.accept("s", "sbx", root).ok());
        assertEquals("latest user edit", Files.readString(root.resolve("a"))); assertNotNull(interrupted.applyState(root));
        Path journal;
        try (var paths = Files.walk(data.resolve("apply-journal"))) {
            journal = paths.filter(path -> path.getFileName().toString().equals("pending.json")).findFirst().orElseThrow();
        }
        Files.writeString(journal, "corrupt");
        assertThrows(IllegalStateException.class, () -> store((p, i) -> {}).accept("s", "sbx", root));
        assertTrue(Files.exists(journal)); assertEquals("latest user edit", Files.readString(root.resolve("a")));
    }

    @Test void failureAfterRevisionPersistenceDoesNotAdvanceRevisionTwice() throws Exception {
        var interrupted = store((point, index) -> { if (point.equals("after-revision")) throw new IOException("模拟元数据写入失败"); });
        interrupted.add("s", candidate()); assertFalse(interrupted.accept("s", "sbx", root).ok());
        assertNotNull(interrupted.applyState(root));
        var restarted = store((point, index) -> {});
        restarted.recoverApply("s", "sbx", root);
        assertEquals(1, restarted.workspaceRevision("s")); assertNull(restarted.applyState(root));
        assertTrue(restarted.accept("s", "sbx", root).written().isEmpty());
    }

    @Test void backupUsesPrivatePermissionsAndRestoredFileKeepsExecutableBitWhenSupported() throws Exception {
        var interrupted = store((point, index) -> { if (point.equals("after-write")) throw new IOException("stop"); });
        interrupted.add("s", candidate());
        var view = Files.getFileAttributeView(root.resolve("a"), java.nio.file.attribute.PosixFileAttributeView.class);
        if (view != null) Files.setPosixFilePermissions(root.resolve("a"), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        assertFalse(interrupted.accept("s", "sbx", root).ok());
        if (view != null) assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("a"))));
        var crashed = store((point, index) -> { if (point.equals("prepared")) throw new AssertionError("simulate crash"); });
        assertThrows(AssertionError.class, () -> crashed.accept("s", "sbx", root));
        Path log;
        try (var paths = Files.walk(data.resolve("apply-journal"))) {
            log = paths.filter(path -> path.getFileName().toString().equals("pending.json")).findFirst().orElseThrow();
        }
        if (view != null) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(log.getParent())));
        }
        store((point, index) -> {}).recoverApply("s", "sbx", root);
    }

}
