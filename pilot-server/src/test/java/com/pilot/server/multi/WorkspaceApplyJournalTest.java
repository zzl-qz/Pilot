package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.server.config.PilotDataDir;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceApplyJournalTest {
    @TempDir
    Path root;
    @TempDir
    Path data;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private SandboxStore store(WorkspaceApplyJournal.FaultPoint faults) {
        return new SandboxStore(mapper, new PilotDataDir(data.toString()),
            new WorkspaceApplyJournal(data.resolve("apply-journal"), mapper, faults));
    }

    private DiffSandbox candidate() throws Exception {
        Files.writeString(root.resolve("a"), "before");
        var box = new DiffSandbox("sbx", "agent", "task", "");
        box.add("a", "before", "after", true, RiskLevel.LOW);
        box.add("new", null, "created", false, RiskLevel.LOW);
        return box;
    }

    @ParameterizedTest
    @CsvSource({"Note.txt, note.txt", "ß.txt, SS.txt", "Σ.txt, ς.txt", "Café.txt, Cafe\u0301.txt"})
    void legacyCaseAliasesAreRejectedBeforeAnyBusinessFileOrJournalIsWritten(String first, String second)
        throws Exception {
        Files.writeString(root.resolve(first), "probe");
        boolean insensitive = Files.exists(root.resolve(second));
        Files.delete(root.resolve(first));
        // 旧版本持久化记录可能已经包含冲突，应用入口仍须独立保护。
        var box = new DiffSandbox("sbx", "agent", "task", "");
        box.add(first, null, "first", false, RiskLevel.LOW);
        box.add(second, null, "second", false, RiskLevel.LOW);
        var store = store((p, i) -> {
        });
        store.add("s", box);
        var result = store.accept("s", "sbx", root);
        assertEquals(!insensitive, result.ok(), result.toString());
        assertNull(store.applyState(root));
        if (insensitive) {
            assertFalse(Files.exists(root.resolve(first)));
            assertEquals(0, store.workspaceRevision("s"));
        } else {
            assertEquals("first", Files.readString(root.resolve(first)));
            assertEquals("second", Files.readString(root.resolve(second)));
        }
    }

    @Test
    void parentAndChildTargetsAreRejectedInEitherOrder() throws Exception {
        for (var paths : List.of(List.of("folder", "folder/child.txt"), List.of("folder/child.txt", "folder"))) {
            var box = new DiffSandbox("sbx", "agent", "task", "");
            paths.forEach(path -> box.add(path, null, "proposal", false, RiskLevel.LOW));
            var store = store((p, i) -> {
            });
            store.add("s", box);
            assertFalse(store.accept("s", "sbx", root).ok());
            assertFalse(Files.exists(root.resolve("folder")));
            assertNull(store.applyState(root));
        }
    }

    @Test
    void restoredSandboxRetainsItsFilesystemPathIdentity() throws Exception {
        Files.writeString(root.resolve("CaseProbe"), "probe");
        boolean insensitive = Files.exists(root.resolve("caseprobe"));
        Files.delete(root.resolve("CaseProbe"));
        var box = new DiffSandbox("sbx", "agent", "task", "", root);
        box.add("Note.txt", null, "proposal", false, RiskLevel.LOW);
        store((p, i) -> {
        }).add("s", box);
        var restarted = store((p, i) -> {
        });
        var restored = restarted.find("s", "sbx").orElseThrow();
        assertEquals(box.workspace(), restored.workspace());
        assertEquals(insensitive, restored.hasChange("note.txt"));
        assertTrue(restored.hasChange("Note.txt"));
        assertEquals(box.candidateHash(), restored.candidateHash());
        assertTrue(restarted.accept("s", "sbx", root).ok());
        assertEquals("proposal", Files.readString(root.resolve("Note.txt")));
        assertNull(restarted.applyState(root));
    }

    @Test
    void preflightConflictDoesNotWriteAnyFile() throws Exception {
        var store = store((p, i) -> {
        });
        store.add("s", candidate());
        Files.writeString(root.resolve("new"), "user");
        assertFalse(store.accept("s", "sbx", root).ok());
        assertEquals("before", Files.readString(root.resolve("a")));
        assertEquals("user", Files.readString(root.resolve("new")));
        assertNull(store.applyState(root));
        assertEquals(0, store.workspaceRevision("s"));
    }

    @Test
    void writeFailureRestoresExistingAndNewFilesAndAllowsRetry() throws Exception {
        var store = store((point, i) -> {
            if (point.equals("after-write") && i == 1) {
                throw new IOException("模拟磁盘故障");
            }
        });
        store.add("s", candidate());
        assertFalse(store.accept("s", "sbx", root).ok());
        assertEquals("before", Files.readString(root.resolve("a")));
        assertFalse(Files.exists(root.resolve("new")));
        assertNull(store.applyState(root));
        var restarted = store((p, i) -> {
        });
        assertTrue(restarted.accept("s", "sbx", root).ok());
        assertEquals("after", Files.readString(root.resolve("a")));
        assertTrue(restarted.accept("s", "sbx", root).written().isEmpty());
        assertEquals(1, restarted.workspaceRevision("s"));
    }

    @Test
    void recoveryNeverOverwritesChangesMadeAfterTheInterruptedOperation() throws Exception {
        var store = store((point, i) -> {
            if (point.equals("after-write") && i == 0) {
                Files.writeString(root.resolve("a"), "user edit");
                throw new IOException("失败");
            }
        });
        store.add("s", candidate());
        assertFalse(store.accept("s", "sbx", root).ok());
        assertNotNull(store.applyState(root));
        var restarted = store((p, i) -> {
        });
        assertThrows(UncheckedIOException.class, () -> restarted.recoverApply("s", "sbx", root));
        assertEquals("user edit", Files.readString(root.resolve("a")));
        assertFalse(Files.exists(root.resolve("new")));
        Files.writeString(root.resolve("a"), "after");
        restarted.recoverApply("s", "sbx", root);
        assertEquals("before", Files.readString(root.resolve("a")));
        assertNull(restarted.applyState(root));
    }

    @Test
    void processExitIsRecoverableBeforeAndAfterJournalCommit() throws Exception {
        for (String phase : List.of("prepared", "after-write", "before-commit", "after-commit", "after-revision",
            "after-metadata")) {
            Path workspace = Files.createDirectory(root.resolve(phase));
            Path dir = Files.createDirectory(data.resolve(phase));
            String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                WorkspaceApplyCrashMain.class.getName(), workspace.toString(), dir.toString(), phase)
                .redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
                assertEquals(17, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            var restored = new SandboxStore(mapper, new PilotDataDir(dir.toString()));
            assertNotNull(restored.applyState(workspace));
            assertThrows(IllegalStateException.class, () -> restored.reset("s"));
            restored.recoverApply("s", "sbx", workspace);
            assertNull(restored.applyState(workspace));
            assertEquals(
                Set.of("after-commit", "after-revision", "after-metadata").contains(phase) ? "after" : "before",
                Files.readString(workspace.resolve("a")));
            assertEquals(Set.of("after-commit", "after-revision", "after-metadata").contains(phase),
                Files.exists(workspace.resolve("new")));
            assertTrue(restored.accept("s", "sbx", workspace).ok());
            assertEquals(1, restored.workspaceRevision("s"));
        }
    }

    @Test
    void concurrentAcceptsSerializeAndApplyOnlyOnce() throws Exception {
        var store = store((p, i) -> {
        });
        store.add("s", candidate());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SandboxStore.ApplyReport>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> store.accept("s", "sbx", root)));
            }
            int writes = 0;
            for (var future : results) {
                var result = future.get(10, TimeUnit.SECONDS);
                assertTrue(result.ok());
                writes += result.written().size();
            }
            assertEquals(2, writes);
            assertEquals(1, store.workspaceRevision("s"));
        }
    }

    @Test
    void invalidVersionAndSymlinkTargetsCannotBeAccepted() throws Exception {
        var store = store((p, i) -> {
        });
        store.add("s", candidate());
        assertThrows(IllegalStateException.class, () -> store.accept("s", "sbx", root, "old-version"));
        Files.delete(root.resolve("a"));
        try {
            Files.createSymbolicLink(root.resolve("a"), data.resolve("secret"));
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("无符号链接权限");
        }
        Files.writeString(data.resolve("secret"), "before");
        assertFalse(store.accept("s", "sbx", root).ok());
        assertEquals("before", Files.readString(data.resolve("secret")));
    }

    @Test
    void changeImmediatelyBeforeWriteIsPreservedAndCorruptJournalFailsClosed() throws Exception {
        var interrupted = store((point, index) -> {
            if (point.equals("before-write")) {
                Files.writeString(root.resolve("a"), "latest user edit");
            }
        });
        interrupted.add("s", candidate());
        assertFalse(interrupted.accept("s", "sbx", root).ok());
        assertEquals("latest user edit", Files.readString(root.resolve("a")));
        assertNotNull(interrupted.applyState(root));
        Path journal;
        try (var paths = Files.walk(data.resolve("apply-journal"))) {
            journal =
                paths.filter(path -> path.getFileName().toString().equals("pending.json")).findFirst().orElseThrow();
        }
        Files.writeString(journal, "corrupt");
        assertThrows(IllegalStateException.class, () -> store((p, i) -> {
        }).accept("s", "sbx", root));
        assertTrue(Files.exists(journal));
        assertEquals("latest user edit", Files.readString(root.resolve("a")));
    }

    @Test
    void failureAfterRevisionPersistenceDoesNotAdvanceRevisionTwice() throws Exception {
        var interrupted = store((point, index) -> {
            if (point.equals("after-revision")) {
                throw new IOException("模拟元数据写入失败");
            }
        });
        interrupted.add("s", candidate());
        assertFalse(interrupted.accept("s", "sbx", root).ok());
        assertNotNull(interrupted.applyState(root));
        var restarted = store((point, index) -> {
        });
        restarted.recoverApply("s", "sbx", root);
        assertEquals(1, restarted.workspaceRevision("s"));
        assertNull(restarted.applyState(root));
        assertTrue(restarted.accept("s", "sbx", root).written().isEmpty());
    }

    @Test
    void backupUsesPrivatePermissionsAndRestoredFileKeepsExecutableBitWhenSupported() throws Exception {
        var interrupted = store((point, index) -> {
            if (point.equals("after-write")) {
                throw new IOException("stop");
            }
        });
        interrupted.add("s", candidate());
        var view = Files.getFileAttributeView(root.resolve("a"), PosixFileAttributeView.class);
        if (view != null) {
            Files.setPosixFilePermissions(root.resolve("a"), PosixFilePermissions.fromString("rwx------"));
        }
        assertFalse(interrupted.accept("s", "sbx", root).ok());
        if (view != null) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("a"))));
        }
        var crashed = store((point, index) -> {
            if (point.equals("prepared")) {
                throw new AssertionError("simulate crash");
            }
        });
        assertThrows(AssertionError.class, () -> crashed.accept("s", "sbx", root));
        Path log;
        try (var paths = Files.walk(data.resolve("apply-journal"))) {
            log = paths.filter(path -> path.getFileName().toString().equals("pending.json")).findFirst().orElseThrow();
        }
        if (view != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log.getParent())));
        }
        store((point, index) -> {
        }).recoverApply("s", "sbx", root);
    }

}
