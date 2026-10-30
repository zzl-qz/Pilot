package com.pilot.core.tool;

import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** run_command 的按命令判定：只读放行、审批、拒绝，以及串联命令的执行语义。 */
class RunCommandPolicyTest {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

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
        for (String command : new String[]{"ls -la", "cat README.md", "rg foo src", "git status --porcelain",
                "git diff --stat | head -20", "git log -5 --oneline", "node --version", "mvn -v",
                "find . -name '*.java'", "wc -l a.txt && echo done", "curl -sS -i http://localhost:3000/health",
                "git branch --list", "git remote -v", "grep -rn \"/api\" src"}) {
            assertTrue(readOnly(command), command);
        }
    }

    @Test
    void commandsWithSideEffectsAreNotReadOnly() {
        for (String command : new String[]{"mvn -q test", "npm test", "node app.js", "python3 -c print(1)",
                "git add .", "git commit -m x", "git branch feature", "git remote add o url", "mkdir build",
                "cp a b", "curl -X POST -d x http://localhost:3000/a", "git diff --output=out.patch",
                "ls && mvn test"}) {
            assertFalse(readOnly(command), command);
        }
    }

    /** 必然失败的命令不该让用户先点一次审批再看到报错。 */
    @Test
    void invalidCommandsPassThroughToReportTheirError() {
        assertTrue(readOnly("rm -rf /"));
        assertTrue(tool.execute(Map.of("command", "rm -rf /")).startsWith("ERROR: 命令不在白名单内"));
    }

    @Test
    void permissionGateUsesThePerCallClassification() {
        Map<String, Object> inspect = Map.of("command", "git status");
        Map<String, Object> build = Map.of("command", "mvn test");
        for (PermissionMode mode : new PermissionMode[]{PermissionMode.READ_ONLY, PermissionMode.ASK}) {
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
        for (String command : new String[]{"cat /etc/passwd", "cat ../secret.txt", "head -n 5 /etc/hosts",
                "cp a.txt /tmp/a.txt", "cp --target-directory=/tmp a.txt", "rg -f /etc/passwd src",
                "ls -t/etc"}) {
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
        assertTrue(tool.execute(Map.of("command", "grep -n -e /api/users routes.txt"))
                .contains("退出码: 0"));
    }

    @Test
    void relativeSymlinksCannotEscapeTheWorkspace() throws Exception {
        Assumptions.assumeFalse(WINDOWS);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.writeString(workspace.resolve("local.txt"), "local");
        Files.createSymbolicLink(workspace.resolve("external"), outside);

        for (String command : new String[]{"cat external/secret.txt", "cp local.txt external/new.txt"}) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: ") && result.contains("工作区内"), command + " -> " + result);
        }
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    @Test
    void dangerousFlagsAreRejected() {
        for (String command : new String[]{"find . -exec cat {} ;", "find . -delete", "rg --pre cat foo",
                "sort -o out.txt a.txt", "tree -o out.txt", "uniq in.txt out.txt"}) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: "), command + " -> " + result);
        }
        assertTrue(tool.execute(Map.of("command", "npm publish")).contains("禁止发布"));
        assertTrue(tool.execute(Map.of("command", "npm install -g typescript")).contains("禁止全局安装"));
    }

    @Test
    void curlOnlyReachesLoopbackAndNeverTouchesLocalFiles() {
        for (String command : new String[]{"curl https://example.com", "curl -o out.html http://localhost:3000",
                "curl -d @secret.txt http://localhost:3000", "curl -x http://proxy http://localhost:3000",
                "curl", "curl http://user:pw@localhost:3000", "curl http://127.0.0.1.evil.com/"}) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: curl"), command + " -> " + result);
        }
        assertEquals(null, CommandPolicy.curl(java.util.List.of("curl", "-sS", "localhost:8080/health")));
        assertEquals(null, CommandPolicy.curl(java.util.List.of("curl", "http://[::1]:8080/")));
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
}
