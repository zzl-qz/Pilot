package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.event.RunStarted;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.workspace.HistoryFileChange;

import tools.jackson.databind.json.JsonMapper;

class HistoryEditCoordinatorTest {
    @TempDir
    Path directory;
    private static final String ID = "history-operation-00001";

    @Test
    void databaseRollbackRestoresFilesAndReadGateExcludesIntermediateState() throws Exception {
        Path target = directory.resolve("tasks/current.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before");
        var store = new SqliteStore(directory.resolve("pilot.db"));
        var data = new PilotDataDir(directory.toString());
        var coordinator = new HistoryEditCoordinator(data, JsonMapper.builder().build(), store, ignored -> {
        });
        assertThrows(IllegalStateException.class,
            () -> coordinator.execute(ID, "s:conversation",
                List.of(new HistoryFileChange(target.getParent(), target, "before".getBytes(), "after".getBytes())),
                "done", mark -> {
                    assertNull(CompletableFuture.supplyAsync(() -> coordinator.tryRead("s")).join());
                    store.transaction(() -> {
                        mark.run();
                        throw new IllegalStateException("提交前失败");
                    });
                }));
        assertEquals("before", Files.readString(target));
        assertTrue(store.historyCommit(ID).isEmpty());
        assertFalse(Files.exists(directory.resolve("history-edits/" + ID + ".pending.json")));
        try (var scope = coordinator.tryRead("s")) {
            assertNotNull(scope);
        }
    }

    @Test
    void actualProcessInterruptionRecoversAllModesAndCommitBoundaries() throws Exception {
        for (String mode : List.of("conversation", "code", "both")) {
            for (String point : List.of("prepared", "file-0", "files-applied", "committed")) {
                Path root = directory.resolve(mode + "-" + point);
                crash(root, point, mode, false);
                var data = new PilotDataDir(root.toString());
                var store = new SqliteStore(root.resolve("pilot.db"));
                var coordinator = new HistoryEditCoordinator(data, JsonMapper.builder().build(), store, ignored -> {
                });
                coordinator.recover();
                coordinator.recover();
                boolean committed = point.equals("committed");
                if (!mode.equals("conversation")) {
                    assertEquals(committed ? "code-after" : "code-before",
                        Files.readString(root.resolve("workspace/code.txt")));
                }
                if (!mode.equals("code")) {
                    assertEquals(committed ? "task-after" : "task-before",
                        Files.readString(root.resolve("tasks/current.json")));
                }
                var sessions = new JdbcSessionRepository(store, new EventCodec(JsonMapper.builder().build()));
                assertEquals(committed && !mode.equals("code") ? 0 : 1, sessions.events("s").size());
                assertEquals(committed && !mode.equals("code") ? 1 : 0, sessions.historyRevision("s"));
                assertFalse(Files.exists(root.resolve("history-edits/" + ID + ".pending.json")));
                assertEquals(committed, coordinator.completed(ID, "s:" + mode, String.class).isPresent());
                if (committed) {
                    assertThrows(IllegalArgumentException.class,
                        () -> coordinator.completed(ID, "different", String.class));
                }
            }
        }
    }

    @Test
    void recoveryCanItselfBeInterruptedAndRetried() throws Exception {
        crash(directory, "files-applied", "both", false);
        crash(directory, "file-0", "both", true);
        new HistoryEditCoordinator(new PilotDataDir(directory.toString()), JsonMapper.builder().build(),
            new SqliteStore(directory.resolve("pilot.db")), ignored -> {
            }).recover();
        assertEquals("code-before", Files.readString(directory.resolve("workspace/code.txt")));
        assertEquals("task-before", Files.readString(directory.resolve("tasks/current.json")));
    }

    @Test
    void preparedJournalFailureIsRecoveredBeforeAnotherOperationCanStart() throws Exception {
        Path target = directory.resolve("workspace/code.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before");
        var data = new PilotDataDir(directory.toString());
        var store = new SqliteStore(directory.resolve("pilot.db"));
        var coordinator = new HistoryEditCoordinator(data, JsonMapper.builder().build(), store, point -> {
            if (point.equals("journal-written")) {
                throw new IllegalStateException("记录写入后持久化失败");
            }
        });
        assertThrows(IllegalStateException.class,
            () -> coordinator.execute(ID, "s:both",
                List.of(new HistoryFileChange(target.getParent(), target, "before".getBytes(), "after".getBytes())),
                "done", coordinator::commitCodeOnly));
        assertEquals("before", Files.readString(target));
        assertFalse(Files.exists(directory.resolve("history-edits/" + ID + ".pending.json")));
        assertTrue(store.historyCommit(ID).isEmpty());
        try (var scope = data.maintenance().tryActivity()) {
            assertNotNull(scope);
        }
    }

    @Test
    void externalEditIsPreservedAndIncompleteRecoveryBlocksStorage() throws Exception {
        Path target = directory.resolve("workspace/code.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before");
        var data = new PilotDataDir(directory.toString());
        var store = new SqliteStore(directory.resolve("pilot.db"));
        var coordinator = new HistoryEditCoordinator(data, JsonMapper.builder().build(), store, point -> {
            if (point.equals("files-applied")) {
                try {
                    Files.writeString(target, "external");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                throw new IllegalStateException("injected");
            }
        });
        assertThrows(IllegalStateException.class,
            () -> coordinator.execute(ID, "s:both",
                List.of(new HistoryFileChange(target.getParent(), target, "before".getBytes(), "after".getBytes())),
                "done", coordinator::commitCodeOnly));
        assertEquals("external", Files.readString(target));
        assertNull(data.maintenance().tryActivity());
        assertTrue(Files.exists(directory.resolve("history-edits/" + ID + ".pending.json")));
        assertThrows(IllegalStateException.class, coordinator::recover);
        assertEquals("external", Files.readString(target));
    }

    private void crash(Path root, String point, String mode, boolean recover) throws Exception {
        Files.createDirectories(root);
        String executable =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path log = root.resolve("process-" + point + "-" + recover + ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            CrashWorker.class.getName(), root.toString(), point, mode, Boolean.toString(recover))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "子进程应及时到达故障点");
            assertEquals(77, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static class CrashWorker {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            String point = args[1];
            String mode = args[2];
            var mapper = JsonMapper.builder().build();
            var store = new SqliteStore(root.resolve("pilot.db"));
            var coordinator = new HistoryEditCoordinator(new PilotDataDir(root.toString()), mapper, store, reached -> {
                if (point.equals(reached)) {
                    Runtime.getRuntime().halt(77);
                }
            });
            if (Boolean.parseBoolean(args[3])) {
                coordinator.recover();
                return;
            }
            var repository = new JdbcSessionRepository(store, new EventCodec(mapper));
            repository.create("s", "原历史", null, Instant.now());
            repository.append("s", "r", Instant.now(), new RunStarted("原消息"), SessionStatus.RUNNING);
            var sessions = new SessionService(Clock.systemUTC(), repository);
            var changes = new ArrayList<HistoryFileChange>();
            for (String kind : List.of("code", "task")) {
                if (mode.equals("conversation") && kind.equals("code") || mode.equals("code") && kind.equals("task")) {
                    continue;
                }
                Path target = root.resolve(kind.equals("code") ? "workspace/code.txt" : "tasks/current.json");
                Files.createDirectories(target.getParent());
                Files.writeString(target, kind + "-before");
                changes.add(new HistoryFileChange(target.getParent(), target, (kind + "-before").getBytes(),
                    (kind + "-after").getBytes()));
            }
            coordinator.execute(ID, "s:" + mode, changes, "done", mark -> {
                if (mode.equals("code")) {
                    coordinator.commitCodeOnly(mark);
                } else {
                    sessions.editHistory("s", 1, 0L, mark);
                }
            });
        }
    }
}
