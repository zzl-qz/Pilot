package com.pilot.core.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class CommandTaskManagerTest {
    @TempDir Path storage;
    @TempDir Path workspace;

    @Test
    void logsArriveBeforeExitWithoutNewlinesAndUnicodePaginationPreservesTrailingOutput() throws Exception {
        Path release = workspace.resolve("release");
        String id;
        try (var manager = manager()) {
            var task = manager.start("session_a", "live unicode", process("live", release.toString()), true, 10_000);
            id = task.taskId();
            waitUntil(() -> manager.logs("session_a", id, 0, 100).text().contains("你好🧪live"));
            assertEquals("RUNNING", manager.get("session_a", id).status());
            assertTrue(manager.hasRunningInWorkspace(workspace));
            var first = manager.logs("session_a", id, 0, 3);
            assertEquals("你好🧪", first.text());
            assertEquals(3, first.nextOffset());
            assertFalse(first.eof());
            Files.createFile(release);
            var completed = awaitDone(manager, "session_a", id);
            assertEquals("COMPLETED", completed.status());
            assertEquals(0, completed.exitCode());
            var last = manager.logs("session_a", id, first.nextOffset(), 100);
            assertEquals("live尾部完成", last.text());
            assertTrue(last.eof());
            assertFalse(last.truncated());
            assertFalse(manager.hasRunningInWorkspace(workspace));
        }
        try (var reloaded = manager()) {
            assertEquals("COMPLETED", reloaded.get("session_a", id).status());
            assertEquals("你好🧪live尾部完成", reloaded.logs("session_a", id, 0, 100).text());
        }
    }

    @Test
    void explicitFailureAndStartFailureRetainTerminalEvidence() throws Exception {
        try (var manager = manager()) {
            var task = manager.start("session_a", "fails", process("failure"), false, 10_000);
            var result = awaitDone(manager, "session_a", task.taskId());
            assertEquals("FAILED", result.status());
            assertEquals(7, result.exitCode());
            assertEquals("failed-tail", manager.logs("session_a", task.taskId(), 0, 100).text());
            var missing = manager.start("session_a", "missing", new ProcessBuilder(workspace.resolve("missing-program").toString())
                    .directory(workspace.toFile()), false, 1_000);
            assertEquals("FAILED", missing.status());
            assertNull(missing.pid());
            assertNotNull(missing.finishedAt());
        }
    }

    @Test
    void timeoutStopsTreeAndManualStopIsSessionScoped() throws Exception {
        try (var manager = manager()) {
            var timed = manager.start("session_a", "timeout", process("hold"), true, 400);
            assertEquals("TIMED_OUT", awaitDone(manager, "session_a", timed.taskId()).status());
            assertFalse(ProcessHandle.of(timed.pid()).map(ProcessHandle::isAlive).orElse(false));
            Path childPidFile = workspace.resolve("child.pid");
            var tree = manager.start("session_a", "tree", process("tree", childPidFile.toString()), true, 10_000);
            waitUntil(() -> Files.exists(childPidFile));
            long childPid = Long.parseLong(Files.readString(childPidFile));
            assertTrue(ProcessHandle.of(childPid).orElseThrow().isAlive());
            assertNull(manager.get("session_b", tree.taskId()));
            assertNull(manager.logs("session_b", tree.taskId(), 0, 100));
            assertNull(manager.stop("session_b", tree.taskId()));
            assertEquals(List.of(), manager.list("session_b"));
            assertEquals("RUNNING", manager.get("session_a", tree.taskId()).status());
            Thread.sleep(100); // allow the descendant tracker to observe the newly-created child
            assertEquals("STOPPING", manager.stop("session_a", tree.taskId()).status());
            assertEquals("CANCELLED", awaitDone(manager, "session_a", tree.taskId()).status());
            waitUntil(() -> !ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(ProcessHandle.of(tree.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void trackedDescendantRemainsOwnedAfterParentExits() throws Exception {
        try (var manager = manager()) {
            Path childPidFile = workspace.resolve("orphan.pid");
            var task = manager.start("session_a", "parent exits", process("orphan", childPidFile.toString()), true, 10_000);
            waitUntil(() -> Files.exists(childPidFile));
            long childPid = Long.parseLong(Files.readString(childPidFile));
            waitUntil(() -> !ProcessHandle.of(task.pid()).map(ProcessHandle::isAlive).orElse(false));
            assertEquals("RUNNING", manager.get("session_a", task.taskId()).status());
            manager.stop("session_a", task.taskId());
            assertEquals("CANCELLED", awaitDone(manager, "session_a", task.taskId()).status());
            waitUntil(() -> !ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void secretSplitAcrossReadsIsNeverPersistedAndMetadataIsRedacted() throws Exception {
        String secret = "split-private-credential-value";
        Path release = workspace.resolve("secret-release");
        try (var manager = manager()) {
            ProcessBuilder builder = process("secret", release.toString());
            builder.environment().put("TASK_TEST_SECRET", secret);
            var task = manager.start("session_a", "print " + secret, builder, true, 10_000);
            assertEquals("print [REDACTED]", task.command());
            waitUntil(() -> manager.logs("session_a", task.taskId(), 0, 100).text().contains("prefix:"));
            assertEquals("prefix:", manager.logs("session_a", task.taskId(), 0, 100).text());
            Files.createFile(release);
            assertEquals("COMPLETED", awaitDone(manager, "session_a", task.taskId()).status());
            assertEquals("prefix:[REDACTED]:suffix", manager.logs("session_a", task.taskId(), 0, 100).text());
            try (var files = Files.walk(storage)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(file);
                    assertFalse(text.contains(secret), file.toString());
                    assertFalse(text.contains("split-private-"), file.toString());
                }
            }
        }
    }

    @Test
    void outputCapTerminatesProcessAndNeverGrowsPastConfiguredDiskLimit() throws Exception {
        try (var manager = new CommandTaskManager(storage, JsonMapper.builder().build(), 1024, 2, 4)) {
            var task = manager.start("session_a", "noisy", process("noisy"), true, 10_000);
            assertEquals("OUTPUT_LIMIT", awaitDone(manager, "session_a", task.taskId()).status());
            var logs = manager.logs("session_a", task.taskId(), 0, 10_000);
            assertTrue(logs.truncated());
            assertTrue(logs.eof());
            Path log = storage.resolve("session_a").resolve(task.taskId() + ".log");
            assertTrue(!Files.exists(log) || Files.size(log) <= 1024);
            assertFalse(ProcessHandle.of(task.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void restartMarksUnownedPidInterruptedWithoutSignallingIt() throws Exception {
        var mapper = JsonMapper.builder().build();
        Path session = Files.createDirectories(storage.resolve("session_a"));
        var stale = new CommandTaskManager.Task("cmd_stale", "session_a", "previous command", workspace.toString(),
                "RUNNING", ProcessHandle.current().pid(), null, Instant.now(), null, true, 60, "");
        Files.writeString(session.resolve("cmd_stale.json"), mapper.writeValueAsString(stale));
        Files.writeString(session.resolve("cmd_stale.log"), "saved🧪");
        try (var manager = manager()) {
            assertEquals("INTERRUPTED", manager.get("session_a", "cmd_stale").status());
            assertTrue(ProcessHandle.current().isAlive());
            assertFalse(manager.hasRunningInWorkspace(workspace));
            assertEquals("saved🧪", manager.logs("session_a", "cmd_stale", 0, 100).text());
            assertTrue(manager.logs("session_a", "cmd_stale", 0, 100).eof());
        }
    }

    @Test
    void corruptTaskMetadataDoesNotBlockRecoveryOrNewCommands() throws Exception {
        Path session = Files.createDirectories(storage.resolve("session_a"));
        Files.writeString(session.resolve("cmd_broken.json"), "{\"taskId\":");
        Files.writeString(session.resolve("cmd_incomplete.json"), "{\"taskId\":\"cmd_incomplete\"}");
        var valid = new CommandTaskManager.Task("cmd_good", "session_a", "prior command", workspace.toString(),
                "COMPLETED", 123L, 0, Instant.now(), Instant.now(), true, 60, "");
        Files.writeString(session.resolve("cmd_good.json"), JsonMapper.builder().build().writeValueAsString(valid));
        try (var manager = manager()) {
            assertEquals(List.of(valid), manager.list("session_a"));
            var fresh = manager.start("session_a", "new command", process("done"), true, 10_000);
            assertEquals("COMPLETED", awaitDone(manager, "session_a", fresh.taskId()).status());
        }
    }

    @Test
    void closingManagerStopsOwnedProcessesAndDeletedSessionCannotRestart() throws Exception {
        Long pid;
        try (var manager = manager()) {
            var task = manager.start("session_a", "hold", process("hold"), true, 10_000);
            pid = task.pid();
            manager.stopSession("session_a");
            assertThrows(IllegalStateException.class,
                    () -> manager.start("session_a", "late", process("hold"), true, 10_000));
            assertEquals("CANCELLED", awaitDone(manager, "session_a", task.taskId()).status());
            pid = manager.start("session_b", "hold", process("hold"), true, 10_000).pid();
        }
        long stopped = pid;
        waitUntil(() -> !ProcessHandle.of(stopped).map(ProcessHandle::isAlive).orElse(false));
        try (var manager = manager()) {
            assertThrows(IllegalStateException.class,
                    () -> manager.start("session_a", "late", process("hold"), true, 10_000));
            assertEquals("INTERRUPTED", manager.list("session_b").getFirst().status());
        }
    }

    @Test
    void backgroundZeroTimeoutRemainsRunningUntilExplicitStop() throws Exception {
        try (var manager = manager()) {
            var task = manager.start("session_a", "persistent server", process("hold"), true, 0);
            assertEquals(0, task.timeoutSeconds());
            Thread.sleep(150);
            assertEquals("RUNNING", manager.get("session_a", task.taskId()).status());
            manager.stop("session_a", task.taskId());
            assertEquals("CANCELLED", awaitDone(manager, "session_a", task.taskId()).status());
        }
    }

    @Test
    void limitsConcurrencyAndRetainedHistoryWithoutDeletingLiveTasks() throws Exception {
        try (var manager = new CommandTaskManager(storage, JsonMapper.builder().build(), 1024, 1, 2)) {
            var first = manager.start("session_a", "hold", process("hold"), true, 10_000);
            assertThrows(IllegalStateException.class,
                    () -> manager.start("session_a", "another", process("hold"), true, 10_000));
            manager.stop("session_a", first.taskId());
            awaitDone(manager, "session_a", first.taskId());
            for (int count = 0; count < 3; count++) {
                var task = manager.start("session_a", "done", process("done"), true, 10_000);
                awaitDone(manager, "session_a", task.taskId());
            }
            assertEquals(2, manager.list("session_a").size());
            assertNull(manager.get("session_a", first.taskId()));
        }
    }

    private CommandTaskManager manager() { return new CommandTaskManager(storage, JsonMapper.builder().build()); }
    private ProcessBuilder process(String... args) {
        var command = new java.util.ArrayList<>(List.of(javaBinary(), "-cp", System.getProperty("java.class.path"), Child.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).directory(workspace.toFile());
    }
    private static String javaBinary() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
    private static CommandTaskManager.Task awaitDone(CommandTaskManager manager, String session, String id) throws Exception {
        waitUntil(() -> !List.of("RUNNING", "STOPPING").contains(manager.get(session, id).status()));
        return manager.get(session, id);
    }
    private static void waitUntil(BooleanSupplier ready) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(ready.getAsBoolean(), "expected command lifecycle boundary was not reached");
    }

    /** Subprocess fixture uses the same JDK as the test, requiring no shell or external interpreter. */
    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "live" -> {
                    System.out.print("你好🧪live"); System.out.flush();
                    untilFile(args[1]);
                    System.err.print("尾部完成"); System.err.flush();
                }
                case "failure" -> { System.out.print("failed-tail"); System.exit(7); }
                case "secret" -> {
                    String secret = System.getenv("TASK_TEST_SECRET");
                    System.out.print("prefix:" + secret.substring(0, 14)); System.out.flush();
                    untilFile(args[1]);
                    System.out.print(secret.substring(14) + ":suffix"); System.out.flush();
                }
                case "tree", "orphan" -> {
                    Process child = new ProcessBuilder(javaBinary(), "-cp", System.getProperty("java.class.path"),
                            Child.class.getName(), "hold").inheritIO().start();
                    Files.writeString(Path.of(args[1]), String.valueOf(child.pid()));
                    if (args[0].equals("orphan")) Thread.sleep(300);
                    else child.waitFor();
                }
                case "noisy" -> {
                    String output = "x".repeat(256);
                    while (true) { System.out.print(output); System.out.flush(); Thread.sleep(1); }
                }
                case "hold" -> Thread.sleep(30_000);
                case "done" -> System.out.print("done");
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
        private static void untilFile(String path) throws Exception {
            while (!Files.exists(Path.of(path))) Thread.sleep(10);
        }
    }
}
