package com.pilot.server.workspace;

import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorktreesTest {

    @TempDir
    Path root;

    @Test
    void opensADetachedCheckoutAndHandsChangesBack() throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "test");
        Files.writeString(repo.resolve("A.txt"), "one\n");
        git(repo, "add", "A.txt");
        git(repo, "commit", "-m", "init");

        GitWorktrees worktrees = new GitWorktrees(new PilotDataDir(root.resolve("data").toString()));
        GitWorktrees.Created created = worktrees.create(repo, "");

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

    private static void git(Path directory, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
