package com.pilot.core.tool.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

class GitReadToolsTest {

    @Test
    void nonGitWorkspaceMarksToolsUnavailableAndReturnsClearErrors(@TempDir Path dir) {
        PathValidator paths = new PathValidator(dir);
        GitStatusTool status = new GitStatusTool(paths);
        GitDiffTool diff = new GitDiffTool(paths);
        GitLogTool log = new GitLogTool(paths);

        assertFalse(status.availability().available());
        assertFalse(diff.availability().available());
        assertFalse(log.availability().available());
        assertTrue(status.execute(Map.of()).startsWith("ERROR:"));
        assertTrue(diff.execute(Map.of()).startsWith("ERROR:"));
        assertTrue(log.execute(Map.of()).startsWith("ERROR:"));
    }

    @Test
    void readsStatusDiffLogWithStagedPathFilterTruncationAndTraversalGuard(@TempDir Path repo) throws Exception {
        git(repo, "init");
        git(repo, "config", "user.name", "Pilot Tester");
        git(repo, "config", "user.email", "tester@pilot.local");

        Path fileA = repo.resolve("a.txt");
        Path fileB = repo.resolve("b.txt");
        Files.writeString(fileA, "line1\nline2\n", StandardCharsets.UTF_8);
        Files.writeString(fileB, "b1\n", StandardCharsets.UTF_8);
        git(repo, "add", "a.txt", "b.txt");
        git(repo, "commit", "-m", "初始化仓库");

        // 暂存修改 b.txt，未暂存修改 a.txt（超过 30 行用于验证截断），外加未跟踪 c.txt
        Files.writeString(fileB, "b1\nb2-staged\n", StandardCharsets.UTF_8);
        git(repo, "add", "b.txt");
        StringBuilder bigA = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            bigA.append("changed-line-").append(i).append('\n');
        }
        Files.writeString(fileA, bigA.toString(), StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("c.txt"), "untracked\n", StandardCharsets.UTF_8);

        PathValidator paths = new PathValidator(repo);
        GitStatusTool status = new GitStatusTool(paths);
        GitDiffTool diff = new GitDiffTool(paths);
        GitLogTool log = new GitLogTool(paths);

        assertTrue(status.availability().available());
        assertTrue(status.readOnly());
        assertTrue(diff.readOnly());
        assertTrue(log.readOnly());

        String statusOut = status.execute(Map.of());
        assertTrue(statusOut.contains("a.txt"), statusOut);
        assertTrue(statusOut.contains("b.txt"), statusOut);
        assertTrue(statusOut.contains("c.txt"), statusOut);

        String unstagedDiff = diff.execute(Map.of("path", "a.txt", "max_lines", 20));
        assertTrue(unstagedDiff.contains("changed-line-1"), unstagedDiff);
        assertTrue(unstagedDiff.contains("仅展示前 20 行"), unstagedDiff);

        String stagedDiff = diff.execute(Map.of("staged", true));
        assertTrue(stagedDiff.contains("b2-staged"), stagedDiff);
        assertFalse(stagedDiff.contains("changed-line-1"), stagedDiff);

        String statDiff = diff.execute(Map.of("stat_only", true));
        assertTrue(statDiff.contains("a.txt"), statDiff);

        String logOut = log.execute(Map.of("limit", 5, "stat", true));
        assertTrue(logOut.contains("初始化仓库"), logOut);
        assertTrue(logOut.contains("a.txt"), logOut);

        // 越界路径被拦截
        assertTrue(status.execute(Map.of("path", "../outside")).startsWith("ERROR:"));
        assertTrue(diff.execute(Map.of("path", "../outside")).startsWith("ERROR:"));
        assertTrue(log.execute(Map.of("path", "../outside")).startsWith("ERROR:"));

        // AgentScope 路径受限时，省略 path 或传 "." 均不能绕过黑白名单
        var scope = AgentScope.readOnly(List.of("a.txt"), List.of("b.txt"));
        GitStatusTool scopedStatus = new GitStatusTool(paths, scope);
        GitDiffTool scopedDiff = new GitDiffTool(paths, scope);
        GitLogTool scopedLog = new GitLogTool(paths, scope);
        assertTrue(scopedStatus.execute(Map.of()).startsWith("ERROR:"));
        assertTrue(scopedStatus.execute(Map.of("path", ".")).startsWith("ERROR:"));
        assertTrue(scopedDiff.execute(Map.of("path", "b.txt")).startsWith("ERROR:"));
        assertTrue(scopedLog.execute(Map.of()).startsWith("ERROR:"));
        assertTrue(scopedStatus.execute(Map.of("path", "a.txt")).contains("a.txt"));
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git failed (" + code + "): " + output);
        }
    }
}
