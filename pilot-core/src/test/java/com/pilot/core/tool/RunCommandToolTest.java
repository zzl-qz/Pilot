package com.pilot.core.tool;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandToolTest {
    @TempDir
    Path workspace;

    @Test
    void rejectsExecutableOutsideWhitelist() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "rm -rf /"));

        assertTrue(result.startsWith("ERROR: 命令不在白名单内"), result);
    }

    @Test
    void rejectsPathInsteadOfBareCommand() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "C:\\Windows\\System32\\cmd.exe /c dir"));

        assertTrue(result.startsWith("ERROR: 只接受白名单内的裸命令名"), result);
    }

    @Test
    void validatesEverySegmentOfAChain() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "git status && rm -rf ."));

        assertTrue(result.startsWith("ERROR: 命令不在白名单内: rm"), result);
    }

    @Test
    void rejectsUnquotedShellSyntax() {
        RunCommandTool tool = tool();

        for (String command : List.of("git status > out.txt", "git status `whoami`", "echo $(whoami)",
                "git status &", "(git status)", "git log < /etc/passwd", "git status ||", "&& git status")) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: "), command + " -> " + result);
            assertFalse(result.contains("退出码"), command + " -> " + result);
        }
    }

    @Test
    void rejectsDangerousGitWriteSubcommands() {
        RunCommandTool tool = tool();

        for (String command : List.of("git push origin main", "git reset --hard HEAD", "git clean -fd")) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: git 只允许只读子命令与 add/commit"), result);
        }
    }

    @Test
    void requiresCommitMessage() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "git commit"));

        assertTrue(result.startsWith("ERROR: git commit 必须带 -m"), result);
    }

    @Test
    void rejectsGitWriteOutsideWorkspace() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "git add ../outside.txt"));

        assertTrue(result.startsWith("ERROR: git add 只允许工作区内的相对路径"), result);
    }

    @Test
    void prefersBundledRuntimeOnPath() throws Exception {
        Path bin = workspace.resolve("runtime-bin");
        java.nio.file.Files.createDirectories(bin);
        // resolveOnPath 在 Windows 只认 .exe/.cmd/.bat（直接执行 Unix 脚本会报 Win32 错误），
        // 测试按平台创建对应的文件名。
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path python = bin.resolve(windows ? "python3.cmd" : "python3");
        java.nio.file.Files.writeString(python,
                windows ? "@echo bundled-python\r\n" : "#!/bin/sh\necho bundled-python\n");
        String previous = System.getProperty("pilot.runtime.bin");
        System.setProperty("pilot.runtime.bin", bin.toString());
        try {
            assertEquals(python.toAbsolutePath(), RunCommandTool.resolveOnPath("python3").toAbsolutePath());
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.runtime.bin");
            } else {
                System.setProperty("pilot.runtime.bin", previous);
            }
        }
    }

    /** mvnw 本身也要映射到工作区 wrapper，而不是去 PATH 里找一个不存在的 mvnw。 */
    @Test
    void mapsMvnwToWorkspaceWrapper() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        java.nio.file.Files.writeString(workspace.resolve("mvnw.cmd"), "@echo wrapper-ok %1\r\n");
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "mvnw -v", "timeoutSeconds", 60));

        assertTrue(result.contains("wrapper-ok"), result);
        assertFalse(result.contains("ERROR: 无法启动命令"), result);
    }

    @Test
    void runsUnixWrapperWithoutExecutablePermission() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path wrapper = workspace.resolve("mvnw");
        java.nio.file.Files.writeString(wrapper, "#!/bin/sh\nprintf 'wrapper-ok:%s\\n' \"$1\"\n");
        assertFalse(java.nio.file.Files.isExecutable(wrapper));
        String result = tool().execute(Map.of("command", "mvn -v", "timeoutSeconds", 10));
        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("wrapper-ok:-v"), result);
        String conventional = tool().execute(Map.of("command", "./mvnw -v", "timeoutSeconds", 10));
        assertTrue(conventional.contains("退出码: 0"), conventional);
        assertTrue(conventional.contains("wrapper-ok:-v"), conventional);
    }

    @Test
    void runsWhitelistedCommandInsideWorkspace() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "java -version", "timeoutSeconds", 60));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("耗时:"), result);
        assertFalse(tool.readOnly());
    }

    @Test
    void runsInRequestedChildDirectoryAndRejectsEscapes() throws Exception {
        Path child = java.nio.file.Files.createDirectory(workspace.resolve("pilot-web"));
        String result = tool().execute(Map.of("command", "node -p process.cwd()", "workdir", "pilot-web"));
        assertTrue(result.contains("退出码: 0"), result);
        // Windows 上临时目录可能以 8.3 短名出现，比较真实路径而不是字符串
        String printed = result.lines().filter(line -> line.contains("工作目录")).findFirst().orElse("")
                .replaceFirst("^.*工作目录[:：]\\s*", "").strip();
        assertEquals(child.toRealPath(), Path.of(printed).toRealPath(), result);
        assertTrue(tool().execute(Map.of("command", "node -v", "workdir", "../outside"))
                .startsWith("ERROR: workdir 无效"));
        assertTrue(tool().execute(Map.of("command", "node -v", "workdir", "missing"))
                .startsWith("ERROR: workdir 不是现有目录"));
        assertTrue(tool().execute(Map.of("command", "npm --prefix ../outside test"))
                .startsWith("ERROR: --prefix 必须指向工作区内的目录"));
    }

    @Test
    void resolvesBuildToolFromPath() {
        Path resolved = RunCommandTool.resolveOnPath("mvn");

        assertNull(RunCommandTool.resolveOnPath("definitely-not-a-real-binary-xyz"));
        assertTrue(resolved != null && resolved.getFileName().toString().toLowerCase().contains("mvn"),
                String.valueOf(resolved));
    }

    @Test
    void runsBuildToolThroughWindowsShell() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "mvn -v", "timeoutSeconds", 90));

        assertTrue(result.contains("退出码: 0"), result);
    }

    /** 子进程不能沿用系统默认的旧 JDK，否则 mvn test 加载不了 Java 24 的字节码。 */
    @Test
    void childProcessUsesTheSameJdkAsPilot() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "java -XshowSettings:properties -version", "timeoutSeconds", 60));

        assertTrue(result.contains("java.home = " + System.getProperty("java.home")), result);
    }

    /** 失败和超时同样要留一行结果：否则「跑过但挂了」在收尾门和界面上都看不见。 */
    @Test
    void reportsCheckSummaryForSuccessAndFailure() {
        ToolCall call = new ToolCall("call_1", "run_command", Map.of("command", "mvn -q test"));
        RunCommandTool tool = tool();

        assertEquals("mvn -q test → exit 0", tool.checkSummary(call, "命令: mvn -q test\n退出码: 0\n耗时: 12 ms"));
        assertEquals("mvn -q test → exit 1", tool.checkSummary(call, "命令: mvn -q test\n退出码: 1\n耗时: 12 ms"));
        String timeout = tool.checkSummary(call, "ERROR: 命令超时（120 秒）已被终止");
        assertTrue(timeout != null && timeout.contains("超时"), String.valueOf(timeout));
        assertNull(tool.checkSummary(call, null));
    }

    @Test
    void rejectsCommandOutsideScope() {
        AgentScope scope = AgentScope.readOnly(List.of(), List.of())
                .withTools(List.of("run_command"), List.of())
                .withCommands(List.of("git status"), List.of());
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace, scope));

        String result = tool.execute(Map.of("command", "mvn -v"));

        assertTrue(result.startsWith("ERROR: 命令 mvn -v 不在你的命令白名单内"), result);
        assertTrue(result.contains("git status"), result);
    }

    @Test
    void readOnlyScopeRejectsGitWrite() {
        AgentScope scope = AgentScope.readOnly(List.of(), List.of())
                .withCommands(List.of("git"), List.of());
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace, scope));

        String result = tool.execute(Map.of("command", "git commit -m \"test\""));

        assertTrue(result.startsWith("ERROR: 只读任务不允许 git commit"), result);
    }

    @Test
    void writableScopeStillAllowsGitWriteChecks() {
        AgentScope scope = AgentScope.readWrite(List.of(), List.of())
                .withCommands(List.of("git commit"), List.of());
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace, scope));

        String result = tool.execute(Map.of("command", "git commit"));

        assertTrue(result.startsWith("ERROR: git commit 必须带 -m"), result);
    }

    @Test
    void tokenizerKeepsQuotedArguments() {
        assertEquals(List.of("git", "commit", "-m", "带 空格 的说明"),
                RunCommandTool.tokenize("git commit -m \"带 空格 的说明\""));
        assertEquals(List.of("mvn", "-q", "test"), RunCommandTool.tokenize("  mvn   -q  test  "));
    }

    private RunCommandTool tool() {
        return new RunCommandTool(new PathValidator(workspace));
    }
}
