package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.pilot.core.session.SessionService;
import com.pilot.server.config.AttachmentLimits;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.AttachmentService;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.workspace.GitWorktrees;

import tools.jackson.databind.json.JsonMapper;

class WorkspaceBackupTest {
    @TempDir
    Path temp;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void restoresSelectedDirectoryAndGitWorktreeWithDetachedCommitAndIndex() throws IOException {
        Path base = repository("基础 仓库");
        Path data = temp.resolve("original-data");
        var pilot = new PilotDataDir(data.toString());
        var git = new GitWorktrees(pilot);
        var created = git.create(base, "HEAD");
        Path worktree = Path.of(created.path());
        Files.writeString(worktree.resolve("commit.txt"), "独立提交\n");
        WorkspaceArchive.gitText(worktree, "add", "commit.txt");
        commit(worktree, "worktree commit");
        String head = WorkspaceArchive.gitText(worktree, "rev-parse", "HEAD");
        Files.writeString(worktree.resolve("tracked.txt"), "staged\n");
        WorkspaceArchive.gitText(worktree, "add", "tracked.txt");
        Files.writeString(worktree.resolve("tracked.txt"), "staged\nunstaged\n");
        byte[] binary = {0, (byte)0xff, 42};
        Files.write(worktree.resolve("未跟踪 文件.bin"), binary);
        Files.writeString(worktree.resolve("intent.txt"), "准备暂存\n");
        WorkspaceArchive.gitText(worktree, "add", "--intent-to-add", "intent.txt");
        Path plain = Files.createDirectory(temp.resolve("普通 工作区"));
        Files.write(plain.resolve("内容.bin"), binary);
        Files.createDirectory(plain.resolve("空目录"));

        SqliteStore sqlite = new SqliteStore(pilot.file("pilot.db"));
        SessionService sessions = sessions(sqlite);
        String session = sessions.createSession("worktree", worktree.toString()).id();
        sessions.createSession("plain", plain.toString());
        BackupService backups = backups(pilot, sqlite);
        var report = backups.backup(List.of(worktree, plain));
        assertTrue(report.ok(), report.message());
        assertTrue(backups.verifyLatestBackup().valid());
        assertEquals(2, report.workspaces().stream().filter(BackupService.WorkspaceStatus::included).count());

        Path newData = temp.resolve("new-data");
        Path newBase = temp.resolve("新 仓库");
        Path newTree = temp.resolve("新 worktree");
        Path newPlain = temp.resolve("新 普通工作区");
        var options = new RestoreService.Options(newData, null, null,
            Map.of(worktree.toString(), newTree, created.base(), newBase, plain.toString(), newPlain));
        Path backup = Path.of(report.directory());
        var restore = new RestoreService(mapper);
        assertFalse(Files.exists(newTree));
        restore.preflight(backup, options);
        assertFalse(Files.exists(newTree));
        restore.restore(backup, options);

        assertEquals(head, WorkspaceArchive.gitText(newTree, "rev-parse", "HEAD"));
        assertEquals("staged", WorkspaceArchive.gitText(newTree, "show", ":tracked.txt"));
        assertEquals("staged\nunstaged\n", Files.readString(newTree.resolve("tracked.txt")));
        assertArrayEquals(binary, Files.readAllBytes(newTree.resolve("未跟踪 文件.bin")));
        assertEquals(" A intent.txt", " " + WorkspaceArchive.gitText(newTree, "status", "--short", "--", "intent.txt"));
        assertArrayEquals(binary, Files.readAllBytes(newPlain.resolve("内容.bin")));
        assertTrue(Files.isDirectory(newPlain.resolve("空目录")));
        assertEquals(newTree.toRealPath().toString(),
            sessions(new SqliteStore(newData.resolve("pilot.db"))).find(session).orElseThrow().workspace());

        var restored = new GitWorktrees(new PilotDataDir(newData.toString()));
        assertTrue(restored.findByPath(newTree.toRealPath()).isPresent());
        assertTrue(restored.preview(newTree.toRealPath()).conflict().isEmpty());
        restored.handoff(newTree.toRealPath());
        assertEquals("staged\nunstaged\n", Files.readString(newBase.resolve("tracked.txt")));
    }

    @Test
    void restoresMetadataAndRetainsAnExistingWorktreeInTheDataDirectory() throws IOException {
        Path base = repository("base");
        Path data = temp.resolve("data");
        var pilot = new PilotDataDir(data.toString());
        var git = new GitWorktrees(pilot);
        var created = git.create(base, "HEAD");
        Path tree = Path.of(created.path());
        Files.writeString(tree.resolve("tracked.txt"), "keep current worktree\n");
        SqliteStore sqlite = new SqliteStore(pilot.file("pilot.db"));
        sessions(sqlite).createSession("worktree", tree.toString());
        var report = backups(pilot, sqlite).backup();
        assertTrue(report.ok(), report.message());
        assertFalse(report.workspaces().getFirst().included());
        Path backup = Path.of(report.directory());
        assertTrue(Files.isRegularFile(backup.resolve("worktrees").resolve(created.id() + ".properties")));
        assertFalse(Files.isDirectory(backup.resolve("worktrees").resolve(created.id())));

        Path movedData = temp.resolve("metadata-only");
        new RestoreService(mapper).restore(backup, new RestoreService.Options(movedData, null, null, Map.of()));
        var rebound = new GitWorktrees(new PilotDataDir(movedData.toString()));
        assertTrue(rebound.findByPath(tree.toRealPath()).isPresent());
        assertTrue(rebound.preview(tree.toRealPath()).conflict().isEmpty());

        new RestoreService(mapper).restore(backup, new RestoreService.Options(data, null, null, Map.of()));

        assertEquals("keep current worktree\n", Files.readString(tree.resolve("tracked.txt")));
        var restored = new GitWorktrees(new PilotDataDir(data.toString()));
        assertTrue(restored.findByPath(tree.toRealPath()).isPresent());
        assertTrue(restored.preview(tree.toRealPath()).conflict().isEmpty());
    }

    @Test
    void refusesToOverwriteAnExistingWorkspaceDuringImport() throws IOException {
        Path data = temp.resolve("source-data");
        Path workspace = Files.createDirectory(temp.resolve("source-workspace"));
        Files.writeString(workspace.resolve("new.txt"), "new");
        var pilot = new PilotDataDir(data.toString());
        SqliteStore sqlite = new SqliteStore(pilot.file("pilot.db"));
        Path backup = Path.of(backups(pilot, sqlite).backup(List.of(workspace)).directory());
        Path existing = Files.createDirectory(temp.resolve("existing-workspace"));
        Files.writeString(existing.resolve("old.txt"), "keep");
        var options =
            new RestoreService.Options(temp.resolve("restored"), null, null, Map.of(workspace.toString(), existing));

        assertThrows(IOException.class, () -> new RestoreService(mapper).restore(backup, options));
        assertEquals("keep", Files.readString(existing.resolve("old.txt")));
        assertFalse(Files.exists(existing.resolve("new.txt")));
    }

    private Path repository(String name) throws IOException {
        Path root = Files.createDirectory(temp.resolve(name));
        WorkspaceArchive.gitText(root, "init", "--template=");
        Files.writeString(root.resolve("tracked.txt"), "base\n");
        WorkspaceArchive.gitText(root, "add", "tracked.txt");
        commit(root, "baseline");
        return root;
    }

    private static void commit(Path root, String message) throws IOException {
        WorkspaceArchive.gitText(root, "-c", "user.name=Pilot Test", "-c", "user.email=pilot@example.invalid", "commit",
            "--no-gpg-sign", "-m", message);
    }

    private SessionService sessions(SqliteStore sqlite) {
        return new SessionService(Clock.systemUTC(), new JdbcSessionRepository(sqlite, new EventCodec(mapper)));
    }

    private BackupService backups(PilotDataDir data, SqliteStore sqlite) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("store", sqlite);
        return new BackupService(beans.getBeanProvider(SqliteStore.class), data, mapper,
            new AttachmentService("", data, AttachmentLimits.defaults()));
    }
}
