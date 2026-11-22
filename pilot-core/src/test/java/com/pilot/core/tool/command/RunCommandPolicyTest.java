package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.ToolCall;
import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.VerificationCheck;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.PathValidator;

/** run_command 的按命令判定：只读放行、审批、拒绝，以及串联命令的执行语义。 */
class RunCommandPolicyTest {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    private RunCommandTool tool;

    @BeforeEach
    void setUp() {
        tool = new RunCommandTool(new PathValidator(workspace));
    }

    private boolean readOnly(String command) {
        return tool.readOnly(Map.of("command", command));
    }

    @Test
    void inspectionCommandsAreReadOnly() {
        for (String command : new String[] {"ls -la", "cat README.md", "rg foo src", "git status --porcelain",
            "git diff --stat | head -20", "git log -5 --oneline", "node --version", "mvn -v", "find . -name '*.java'",
            "wc -l a.txt && echo done", "curl -sS -i http://localhost:3000/health", "git branch --list",
            "git remote -v", "grep -rn \"/api\" src"}) {
            assertTrue(readOnly(command), command);
        }
    }

    @Test
    void commandsWithSideEffectsAreNotReadOnly() {
        for (String command : new String[] {"mvn -q test", "npm test", "node app.js", "python3 -c print(1)",
            "git add .", "git commit -m x", "git branch feature", "git remote add o url", "mkdir build", "cp a b",
            "curl -X POST -d x http://localhost:3000/a", "git diff --output=out.patch", "ls && mvn test"}) {
            assertFalse(readOnly(command), command);
        }
    }

    /** 必然失败的命令不该让用户先点一次审批再看到报错。 */
    @Test
    void invalidCommandsPassThroughToReportTheirError() {
        assertTrue(readOnly("rm -rf /"));
        String rm = tool.execute(Map.of("command", "rm -rf /"));
        assertTrue(rm.startsWith("ERROR: ") && rm.contains("工作区外"), rm);
    }

    @Test
    void permissionGateUsesThePerCallClassification() {
        Map<String, Object> inspect = Map.of("command", "git status");
        Map<String, Object> build = Map.of("command", "mvn test");
        for (PermissionMode mode : new PermissionMode[] {PermissionMode.READ_ONLY, PermissionMode.ASK}) {
            PolicyPermissionGate gate = new PolicyPermissionGate(mode);
            assertEquals(PermissionDecision.ALLOW,
                gate.check(new ToolCall("1", "run_command", inspect), tool).decision(), mode.name());
        }
        assertEquals(PermissionDecision.DENY, new PolicyPermissionGate(PermissionMode.READ_ONLY)
            .check(new ToolCall("2", "run_command", build), tool).decision());
        assertEquals(PermissionDecision.ASK, new PolicyPermissionGate(PermissionMode.ASK)
            .check(new ToolCall("3", "run_command", build), tool).decision());
        assertEquals(PermissionDecision.ASK, new PolicyPermissionGate(PermissionMode.AUTO_EDIT)
            .check(new ToolCall("4", "run_command", build), tool).decision());
    }

    @Test
    void fileCommandsCannotLeaveTheWorkspace() {
        for (String command : new String[] {"cat /etc/passwd", "cat ../secret.txt", "head -n 5 /etc/hosts",
            "cp a.txt /tmp/a.txt", "cp --target-directory=/tmp a.txt", "rg -f /etc/passwd src", "ls -t/etc"}) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: ") && result.contains("工作区内"), command + " -> " + result);
        }
    }

    @Test
    void searchPatternIsNotTreatedAsAPath() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(workspace.resolve("routes.txt"), "GET /api/users\nGET /health\n");

        String result = tool.execute(Map.of("command", "grep -n /api/users routes.txt"));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("1:GET /api/users"), result);
        assertTrue(tool.execute(Map.of("command", "grep -n -e /api/users routes.txt")).contains("退出码: 0"));
    }

    @Test
    void relativeSymlinksCannotEscapeTheWorkspace() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.writeString(workspace.resolve("local.txt"), "local");
        Files.createSymbolicLink(workspace.resolve("external"), outside);

        for (String command : new String[] {"cat external/secret.txt", "cp local.txt external/new.txt"}) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: ") && result.contains("工作区内"), command + " -> " + result);
        }
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    @Test
    void safeEnvPrefixRunsAndDangerousPrefixIsRejected() {
        Assumptions.assumeFalse(WINDOWS);
        Assumptions.assumeTrue(CommandRuntimeEnvironment.resolveOnPath("node", System.getenv("PATH"), WINDOWS) != null);

        String result = tool.execute(Map.of("command", "NODE_ENV=from-prefix node -p process.env.NODE_ENV"));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("from-prefix"), result);
        assertTrue(tool.execute(Map.of("command", "LD_PRELOAD=/bad.so ls")).contains("LD_PRELOAD"));
        assertTrue(tool.execute(Map.of("command", "DYLD_INSERT_LIBRARIES=/bad.dylib ls")).contains("DYLD_"));
        assertTrue(tool.execute(Map.of("command", "PATH=/tmp ls")).contains("PATH"));
        assertTrue(tool.execute(Map.of("command", "sudo ls")).contains("提权"));
    }

    @Test
    void workspaceRedirectWritesAndOutsideRedirectIsRejected() throws Exception {
        Assumptions.assumeFalse(WINDOWS);

        String inside = tool.execute(Map.of("command", "echo hello > note.txt"));
        assertTrue(inside.contains("退出码: 0"), inside);
        assertTrue(Files.readString(workspace.resolve("note.txt")).contains("hello"));

        String outsideFile = outside.resolve("nope.txt").toString().replace("\"", "");
        String outsideResult = tool.execute(Map.of("command", "echo hi > \"" + outsideFile + "\""));
        assertTrue(outsideResult.contains("工作区内"), outsideResult);
        assertFalse(Files.exists(outside.resolve("nope.txt")));
    }

    @Test
    void deletingOutsideTheWorkspaceIsRejected() {
        String result = tool.execute(Map.of("command", "rm \"" + outside.resolve("secret.txt") + "\""));

        assertTrue(result.contains("工作区外"), result);
        assertFalse(result.contains("白名单"), result);
    }

    @Test
    void repeatedReadOnlyCommandIsNotRunAgain() {
        Assumptions.assumeFalse(WINDOWS);
        String first = tool.execute(Map.of("command", "echo same"));
        assertTrue(first.contains("退出码: 0"), first);

        String second = tool.execute(Map.of("command", "echo same"));

        assertTrue(second.contains("未再执行"), second);
        assertFalse(second.contains("退出码"));
    }

    @Test
    void deletingInsideTheWorkspaceRuns() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(workspace.resolve("gone.txt"), "x");

        String result = tool.execute(Map.of("command", "rm gone.txt"));

        assertTrue(result.contains("退出码: 0"), result);
        assertFalse(Files.exists(workspace.resolve("gone.txt")));
    }

    @Test
    void dangerousFlagsAreRejected() {
        for (String command : new String[] {"find . -exec cat {} ;", "find . -delete", "rg --pre cat foo",
            "sort -o out.txt a.txt", "tree -o out.txt", "uniq in.txt out.txt"}) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: "), command + " -> " + result);
        }
        assertTrue(tool.confirm(Map.of("command", "npm publish")));
        assertTrue(tool.confirm(Map.of("command", "npm install -g typescript")));
    }

    @Test
    void curlOnlyReachesLoopbackAndNeverTouchesLocalFiles() {
        for (String command : new String[] {"curl https://example.com", "curl -o out.html http://localhost:3000",
            "curl -d @secret.txt http://localhost:3000", "curl -x http://proxy http://localhost:3000", "curl",
            "curl http://user:pw@localhost:3000", "curl http://127.0.0.1.evil.com/"}) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: curl"), command + " -> " + result);
        }
        assertEquals(null, CommandPolicy.curl(List.of("curl", "-sS", "localhost:8080/health")));
        assertEquals(null, CommandPolicy.curl(List.of("curl", "http://[::1]:8080/")));
    }

    @Test
    void leadingCdBecomesWorkdirInsideTheWorkspace() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Path child = Files.createDirectory(workspace.resolve("web"));

        String result = tool.execute(Map.of("command", "cd web && node -p process.cwd()"));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains(child.toRealPath().toString()), result);
        assertTrue(tool.execute(Map.of("command", "cd ../.. && ls")).startsWith("ERROR: cd 目标无效"));
        assertTrue(tool.execute(Map.of("command", "cd web")).startsWith("ERROR: cd 只能写成"));
    }

    @Test
    void chainedCommandsRunSequentiallyWithPipesAndConnectors() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(workspace.resolve("a.txt"), "one\ntwo\nthree\n");

        String piped = tool.execute(Map.of("command", "cat a.txt | head -2"));
        assertTrue(piped.contains("退出码: 0"), piped);
        assertTrue(piped.contains("one\ntwo") && !piped.contains("three"), piped);

        String and = tool.execute(Map.of("command", "ls a.txt && echo after"));
        assertTrue(and.contains("after"), and);

        String stops = tool.execute(Map.of("command", "ls missing-file && echo after"));
        assertFalse(stops.substring(stops.indexOf("输出:")).contains("after"), stops);

        String fallback = tool.execute(Map.of("command", "ls missing-file 2>&1 || echo fallback"));
        assertTrue(fallback.contains("fallback"), fallback);
    }

    @Test
    void chainedArgumentsAppearExactlyOnceIncludingTailAndQuotedWords() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(workspace.resolve("a.txt"), "one\ntwo\nthree\n");
        String piped = tool.execute(Map.of("command", "cat a.txt | tail -n 2"));
        assertTrue(piped.contains("退出码: 0"), piped);
        assertEquals("two\nthree", piped.substring(piped.indexOf("输出:\n") + "输出:\n".length()));
        String sequence = tool.execute(Map.of("command", "echo 'a b' && echo 'c;d'"));
        assertEquals("a b\nc;d", sequence.substring(sequence.indexOf("输出:\n") + "输出:\n".length()));
    }

    @Test
    void chainedWordsAreNeverExpandedByTheShell() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(workspace.resolve("a.txt"), "x");

        String result = tool.execute(Map.of("command", "echo '$HOME' \"$(whoami)\" *.txt && echo '; touch pwned'"));

        assertTrue(result.contains("$HOME $(whoami) *.txt"), result);
        assertTrue(result.contains("; touch pwned"), result);
        assertFalse(Files.exists(workspace.resolve("pwned")));
    }

    @Test
    void readOnlyCommandsDoNotReportWorkspaceChanges() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(workspace.resolve("a.txt"), "x");
        ToolResult result = tool.executeResult(Map.of("command", "cat a.txt"));

        assertTrue(result.changedPaths().isEmpty());
        assertTrue(result.text().contains("退出码: 0"), result.text());
    }

    @Test
    void liveProbesAreNotCached() {
        assertFalse(RunCommandTool.cachesReadOnlyCommand("curl -sS http://localhost:5185/"));
        assertFalse(RunCommandTool.cachesReadOnlyCommand("lsof -nP -iTCP:5185"));
        assertFalse(RunCommandTool.cachesReadOnlyCommand("ps aux"));
        assertTrue(RunCommandTool.cachesReadOnlyCommand("git status --porcelain"));
    }

    @Test
    void workspaceWriteInvalidatesTheReadOnlyCommandCache() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        ReadTracker tracker = new ReadTracker();
        RunCommandTool cached = new RunCommandTool(new PathValidator(workspace)).withReads(tracker);

        String first = cached.execute(Map.of("command", "echo same"));
        assertTrue(first.contains("退出码: 0"), first);
        Files.writeString(workspace.resolve("note.txt"), "changed");
        tracker.observed(workspace.resolve("note.txt"));
        String again = cached.execute(Map.of("command", "echo same"));

        assertTrue(again.contains("退出码: 0"), again);
        assertFalse(again.contains("未再执行"));
    }

    @Test
    void readOnlyCommandCacheExpires() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        tool.readOnlyCacheTtlMs = -1L;

        String first = tool.execute(Map.of("command", "echo same"));
        String second = tool.execute(Map.of("command", "echo same"));

        assertTrue(first.contains("退出码: 0"), first);
        assertTrue(second.contains("退出码: 0"), second);
        assertFalse(second.contains("未再执行"));
    }

    @Test
    void catOfFullyDeliveredFileIsDedupedButPartialOrPostCompactReadIsAllowed() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        ReadTracker tracker = new ReadTracker();
        PathValidator validator = new PathValidator(workspace);
        ReadFileTool reader = new ReadFileTool(validator, tracker);
        RunCommandTool command = new RunCommandTool(validator).withReads(tracker);
        Files.writeString(workspace.resolve("App.java"), "one\ntwo\nthree\nfour\n");

        // 仅读了前 2 行时，不应拦截命令行查看剩余内容
        reader.execute(Map.of("path", "App.java", "start_line", 1, "line_count", 2));
        String partial = command.execute(Map.of("command", "cat App.java"));
        assertTrue(partial.contains("退出码: 0"), partial);

        // 完整交付后，重复 cat 被提示复用已交付内容
        reader.execute(Map.of("path", "App.java", "start_line", 3, "line_count", 2));
        String dumped = command.execute(Map.of("command", "cat -n App.java | sed -n '1,4p'"));
        assertTrue(dumped.contains("未变化"), dumped);
        assertTrue(dumped.contains("1-4"), dumped);
        assertFalse(dumped.contains("退出码"));

        // 上下文压缩失效视图后，重新允许读取
        tracker.invalidateDeliveredViews();
        String afterCompact = command.execute(Map.of("command", "cat -n App.java | sed -n '1,4p'"));
        assertTrue(afterCompact.contains("退出码: 0"), afterCompact);
    }

    @Test
    void distinctGitHistoryCommandsAreNotBlockedByHardCapWhileIdenticalCommandStillDedups() {
        Assumptions.assumeFalse(WINDOWS);
        String fifth = "";
        for (int i = 1; i <= 5; i++) {
            fifth = tool.execute(Map.of("command", "git log --oneline -" + i));
        }
        assertFalse(fifth.contains("提交历史已经看过"), fifth);
        assertFalse(fifth.startsWith("与上次相同，未再执行"), fifth);

        // 相同只读命令在 TTL 内仍走缓存去重（非 git 仓库返回非 0 退出码不缓存，用 echo 锁定）
        String first = tool.execute(Map.of("command", "echo git-cache-check"));
        String second = tool.execute(Map.of("command", "echo git-cache-check"));
        assertTrue(first.contains("退出码: 0"), first);
        assertTrue(second.startsWith("与上次相同，未再执行"), second);
    }

    @Test
    void longMavenTestCommandPreservesTrailingGoalForVerificationClassification() {
        String longCmd =
            "mvnw -q -pl pilot-server -Dtest=AgentPropertiesBindingTest,AgentPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test";
        String summary = CommandToolSupport.summarizeCommand(longCmd) + " → exit 0";
        assertEquals(VerificationCheck.Kind.TEST, VerificationCheck.passingKind(summary), summary);

        String hugeCmd = "mvnw -q -pl pilot-server -Dtest=" + "VeryLongTestClassName,".repeat(15)
            + "AgentPropertiesBindingTest -Dsurefire.failIfNoSpecifiedTests=false test";
        String hugeSummary = CommandToolSupport.summarizeCommand(hugeCmd) + " → exit 0";
        assertEquals(VerificationCheck.Kind.TEST, VerificationCheck.passingKind(hugeSummary), hugeSummary);
    }
}
