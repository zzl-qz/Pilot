package com.pilot.server.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.RunRegistry;

class GitWorktreesTest {

    @TempDir
    Path root;

    @Test
    void detectsUnbornBranchAndNonRepositoryWithoutChangingFiles() throws Exception {
        GitWorktrees service = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()));
        assertEquals("", service.currentBranch(root));
        assertEquals("", service.currentBranch(root.resolve("missing")));
        git(root, "init", "--initial-branch=feature/ui");
        assertEquals("feature/ui", service.currentBranch(root));
    }

    @Test
    void opensADetachedCheckoutAndHandsChangesBack() throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "test");
        // Windows 的 Git 默认 core.autocrlf=true，检出会把 LF 改成 CRLF；用例比较的是文件内容，
        // 必须让测试仓库自己决定换行，不跟随本机全局配置。
        git(repo, "config", "core.autocrlf", "false");
        Files.writeString(repo.resolve("A.txt"), "one\n");
        git(repo, "add", "A.txt");
        git(repo, "commit", "-m", "init");

        GitWorktrees worktrees = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()));
        GitWorktrees.Created created = worktrees.create(repo, "");

        assertTrue(worktrees.currentBranch(Path.of(created.path())).startsWith("detached @ "));

        assertFalse(created.path().startsWith(repo.toString()));
        assertEquals("one\n", Files.readString(Path.of(created.path(), "A.txt")));
        assertTrue(worktrees.findByPath(Path.of(created.path())).isPresent());

        Files.writeString(Path.of(created.path(), "A.txt"), "two\n");
        Files.writeString(Path.of(created.path(), "B.txt"), "new\n");
        GitWorktrees.Handoff handed = worktrees.handoff(Path.of(created.path()));

        assertEquals(repo.toRealPath().toString(), Path.of(handed.base()).toRealPath().toString());
        assertEquals("two\n", Files.readString(repo.resolve("A.txt")));
        assertEquals("new\n", Files.readString(repo.resolve("B.txt")));
        assertTrue(Files.exists(Path.of(created.path())));

        worktrees.remove(Path.of(created.path()));
        assertTrue(worktrees.findByPath(Path.of(created.path())).isEmpty());
    }

    private Path repository() throws Exception {
        Path repo = Files.createDirectories(root.resolve("原项目 with spaces"));
        git(repo, "init");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "test");
        git(repo, "config", "core.autocrlf", "false");
        Files.writeString(repo.resolve("A.txt"), "one\n");
        Files.writeString(repo.resolve("删除.txt"), "old\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "初始版本");
        return repo;
    }

    @Test
    void previewIncludesCommitsBinaryDeletionAndNewFilesWithoutChangingRealIndex() throws Exception {
        Path repo = repository();
        var service = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()));
        Path copy = Path.of(service.create(repo, "HEAD").path());
        Files.writeString(copy.resolve("A.txt"), "committed\n");
        git(copy, "add", ".");
        git(copy, "commit", "-m", "提交任务改动");
        Files.delete(copy.resolve("删除.txt"));
        byte[] binary = new byte[] {0, 1, -1, 0, 50};
        Files.write(copy.resolve("新增 文件.bin"), binary);
        Path index = Path.of(Files.readString(copy.resolve(".git")).strip().substring(8)).resolve("index");
        byte[] before = Files.readAllBytes(index);
        var preview = service.preview(copy);
        assertTrue(preview.files().containsAll(List.of("A.txt", "删除.txt", "新增 文件.bin")));
        assertEquals("", preview.conflict());
        assertArrayEquals(before, Files.readAllBytes(index));
        assertEquals("one\n", Files.readString(repo.resolve("A.txt")));
        service.handoff(copy, preview.revision());
        assertEquals("committed\n", Files.readString(repo.resolve("A.txt")));
        assertArrayEquals(binary, Files.readAllBytes(repo.resolve("新增 文件.bin")));
        assertFalse(Files.exists(repo.resolve("删除.txt")));
        assertArrayEquals(before, Files.readAllBytes(index));
    }

    @Test
    void conflictAndStalePreviewNeverPartiallyApply() throws Exception {
        Path repo = repository();
        var service = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()));
        Path copy = Path.of(service.create(repo, "HEAD").path());
        Files.writeString(copy.resolve("A.txt"), "changed\n");
        Files.writeString(copy.resolve("new.txt"), "agent\n");
        var clean = service.preview(copy);
        Files.writeString(repo.resolve("new.txt"), "user\n");
        assertThrows(IllegalStateException.class, () -> service.handoff(copy, clean.revision()));
        var conflict = service.preview(copy);
        assertFalse(conflict.conflict().isBlank());
        assertThrows(IllegalArgumentException.class, () -> service.handoff(copy, conflict.revision()));
        assertEquals("one\n", Files.readString(repo.resolve("A.txt")));
        assertEquals("user\n", Files.readString(repo.resolve("new.txt")));
        Files.delete(repo.resolve("new.txt"));
        var preview = service.preview(copy);
        Files.writeString(copy.resolve("A.txt"), "later\n");
        assertThrows(IllegalStateException.class, () -> service.handoff(copy, preview.revision()));
        assertEquals("one\n", Files.readString(repo.resolve("A.txt")));
    }

    @Test
    void activeWorkspaceRefusesHandoffAndReleasesPartialLock() throws Exception {
        Path repo = repository();
        var registry = new RunRegistry();
        var service = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()), registry);
        Path copy = Path.of(service.create(repo, "HEAD").path());
        Files.writeString(copy.resolve("A.txt"), "changed\n");
        assertTrue(registry.lockWorkspace("busy", repo));
        assertThrows(IllegalStateException.class, () -> service.handoff(copy));
        assertTrue(registry.lockWorkspace("probe", copy));
        registry.unregister("probe");
        registry.unregister("busy");
        service.handoff(copy);
        assertEquals("changed\n", Files.readString(repo.resolve("A.txt")));
    }

    @Test
    void backgroundCommandsBlockHandoffEvenAfterAgentEnds() throws Exception {
        Path repo = repository();
        var registry = new RunRegistry();
        var commands = Mockito.mock(CommandTaskManager.class);
        var service = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()), registry, commands);
        Path copy = Path.of(service.create(repo, "HEAD").path());
        Files.writeString(copy.resolve("A.txt"), "finished\n");
        Mockito.when(commands.hasRunningInWorkspace(copy)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.handoff(copy));
        assertEquals("one\n", Files.readString(repo.resolve("A.txt")));
        Mockito.when(commands.hasRunningInWorkspace(copy)).thenReturn(false);
        service.handoff(copy);
        assertEquals("finished\n", Files.readString(repo.resolve("A.txt")));
    }

    @Test
    void symlinkDestinationIsRejectedWithoutWritingOutsideWorkspace() throws Exception {
        Path repo = repository();
        var service = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()));
        Path copy = Path.of(service.create(repo, "HEAD").path());
        Files.writeString(copy.resolve("A.txt"), "changed\n");
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Files.delete(repo.resolve("A.txt"));
        try {
            Files.createSymbolicLink(repo.resolve("A.txt"), outside);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("平台不允许创建符号链接");
        }
        assertThrows(IllegalArgumentException.class, () -> service.preview(copy));
        assertEquals("outside", Files.readString(outside));
    }

    private static void git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
