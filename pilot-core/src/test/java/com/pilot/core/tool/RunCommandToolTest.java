package com.pilot.core.tool;

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
        var tool = tool();

        String result = tool.execute(Map.of("command", "rm -rf /"));

        assertTrue(result.startsWith("ERROR: 命令不在白名单内"), result);
    }

    @Test
    void rejectsPathInsteadOfBareCommand() {
        var tool = tool();

        String result = tool.execute(Map.of("command", "C:\\Windows\\System32\\cmd.exe /c dir"));

        assertTrue(result.startsWith("ERROR: 只接受白名单内的裸命令名"), result);
    }

    @Test
    void rejectsShellMetacharacters() {
        var tool = tool();

        String result = tool.execute(Map.of("command", "git status && rm -rf ."));

        assertTrue(result.startsWith("ERROR: 命令包含不允许的 shell 元字符"), result);
    }

    @Test
    void rejectsGitWriteSubcommands() {
        var tool = tool();

        String result = tool.execute(Map.of("command", "git commit -m test"));

        assertTrue(result.startsWith("ERROR: git 只允许只读子命令"), result);
    }

    @Test
    void runsWhitelistedCommandInsideWorkspace() {
        var tool = tool();

        String result = tool.execute(Map.of("command", "java -version", "timeoutSeconds", 60));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("耗时:"), result);
        assertFalse(tool.readOnly());
    }

    @Test
    void resolvesBuildToolFromPath() {
        var resolved = RunCommandTool.resolveOnPath("mvn");

        assertNull(RunCommandTool.resolveOnPath("definitely-not-a-real-binary-xyz"));
        assertTrue(resolved != null && resolved.getFileName().toString().toLowerCase().contains("mvn"),
                String.valueOf(resolved));
    }

    @Test
    void runsBuildToolThroughWindowsShell() {
        var tool = tool();

        String result = tool.execute(Map.of("command", "mvn -v", "timeoutSeconds", 90));

        assertTrue(result.contains("退出码: 0"), result);
    }

    /** 子进程不能沿用系统默认的旧 JDK，否则 mvn test 加载不了 Java 24 的字节码。 */
    @Test
    void childProcessUsesTheSameJdkAsPilot() {
        var tool = tool();

        String result = tool.execute(Map.of("command", "java -XshowSettings:properties -version", "timeoutSeconds", 60));

        assertTrue(result.contains("java.home = " + System.getProperty("java.home")), result);
    }

    @Test
    void reportsCheckSummaryOnlyForSuccessfulRuns() {
        var call = new ToolCall("call_1", "run_command", Map.of("command", "mvn -q test"));
        var tool = tool();

        assertEquals("mvn -q test → exit 0", tool.checkSummary(call, "命令: mvn -q test\n退出码: 0\n耗时: 12 ms"));
        assertNull(tool.checkSummary(call, "ERROR: 命令超时"));
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
