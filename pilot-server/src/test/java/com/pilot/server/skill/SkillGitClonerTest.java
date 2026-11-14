package com.pilot.server.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillGitClonerTest {
    @TempDir
    Path temp;

    private String git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output.strip();
    }

    @Test
    void checksOutRequestedBranchAndCommitWithPortablePaths() throws Exception {
        Path source = Files.createDirectories(temp.resolve("来源 with spaces"));
        git(source, "init", "--initial-branch=main");
        git(source, "config", "user.name", "test");
        git(source, "config", "user.email", "test@example.invalid");
        Path file = Files.createDirectories(source.resolve("skills/demo")).resolve("SKILL.md");
        Files.writeString(file, "first");
        Files.createDirectories(source.resolve("other"));
        Files.writeString(source.resolve("other/private.txt"), "unselected");
        git(source, "add", ".");
        git(source, "commit", "-m", "first");
        String commit = git(source, "rev-parse", "HEAD");
        git(source, "checkout", "-b", "feature/skills");
        Files.writeString(file, "second");
        git(source, "commit", "-am", "second");
        var cloner = new SkillInstallService.GitCloner();
        Path branchTarget = temp.resolve("branch result");
        cloner.clone(source.toUri().toString(), branchTarget, "feature/skills", "skills/demo");
        assertEquals("second", Files.readString(branchTarget.resolve("skills/demo/SKILL.md")));
        assertFalse(Files.exists(branchTarget.resolve("other/private.txt")));
        Path commitTarget = temp.resolve("commit result");
        cloner.clone(source.toUri().toString(), commitTarget, commit, "skills/demo");
        assertEquals("first", Files.readString(commitTarget.resolve("skills/demo/SKILL.md")));
    }
}
