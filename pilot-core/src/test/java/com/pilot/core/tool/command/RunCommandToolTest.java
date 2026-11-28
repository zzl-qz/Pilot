package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.ToolCall;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

class RunCommandToolTest {
    @TempDir
    Path workspace;

    @Test
    void rejectsExecutableOutsideWhitelist() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "rm -rf /"));

        assertTrue(result.startsWith("ERROR: ") && result.contains("工作区外"), result);
    }

    @Test
    void missingUnixFileCommandPointsAtFileTools() {
        String text = RunCommandTool
            .launchFailureText("ERROR: 无法启动命令 ls：Cannot run program \"ls\": CreateProcess error=2", "ls", 1);

        assertTrue(text.contains("list_dir / read_file / search_text"), text);
        assertFalse(text.contains("不要原样重试"), "第一次不该升级成「不要重试」: " + text);
    }

    @Test
    void missingWindowsRmSuggestsExplicitPowerShellRemoval() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));

        String text = RunCommandTool
            .launchFailureText("ERROR: 无法启动命令 rm：Cannot run program \"rm\": CreateProcess error=2", "rm", 1);

        assertTrue(text.contains("PowerShell Remove-Item -LiteralPath"), text);
        assertTrue(text.contains("不要原样重试 rm"), text);
    }

    @Test
    void secondMissingUnixFileCommandAsksToStopRetrying() {
        String text = RunCommandTool
            .launchFailureText("ERROR: 无法启动命令 cat：Cannot run program \"cat\": CreateProcess error=2", "cat", 2);

        assertTrue(text.contains("不要原样重试"), text);
        // Windows 给 PowerShell 等价命令；macOS/Linux 上 cat 本来就有，不能再写「Windows 上」。
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            assertTrue(text.contains("PowerShell 等价命令"), text);
        } else {
            assertFalse(text.contains("Windows"), text);
        }
    }

    /** 缺 rg 在三个系统上都可能发生，纠偏该指向 search_text 而不是只提文件工具。 */
    @Test
    void missingSearchCommandPointsAtSearchText() {
        String text = RunCommandTool
            .launchFailureText("ERROR: 无法启动命令 rg：Cannot run program \"rg\": CreateProcess error=2", "rg", 1);

        assertTrue(text.contains("search_text"), text);
        assertTrue(text.contains("glob_files"), text);
        assertFalse(text.contains("Windows 上 ls"), text);
    }

    @Test
    void onlyMissingUnixFileCommandsCountAsCorrectable() {
        assertTrue(RunCommandTool.missingFileCommand("ls",
            "Cannot run program \"ls\" (in directory \"E:\\work\"): CreateProcess error=2, 系统找不到指定的文件。"));
        assertTrue(
            RunCommandTool.missingFileCommand("rg", "Cannot run program \"rg\": error=2, No such file or directory"));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        assertEquals(windows,
            RunCommandTool.missingFileCommand("rm", "Cannot run program \"rm\": CreateProcess error=2"));
        assertFalse(RunCommandTool.missingFileCommand("mvn", "Cannot run program \"mvn\": CreateProcess error=2"));
        assertFalse(RunCommandTool.missingFileCommand("cat", "Not a directory"));
    }

    @Test
    void pathCommandIsNotRejectedForItsName() {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "/bin/echo ok"));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("ok"), result);
        assertFalse(result.contains("白名单"), result);
    }

    @Test
    void validatesEverySegmentOfAChain() throws Exception {
        RunCommandTool tool = tool();
        Files.writeString(workspace.resolve("gone.txt"), "x");

        String kept = tool.execute(Map.of("command", "echo ok && rm gone.txt"));
        assertTrue(kept.contains("退出码: 0"), kept);
        assertFalse(kept.contains("白名单"), kept);
        assertFalse(Files.exists(workspace.resolve("gone.txt")));

        String outside = tool.execute(Map.of("command", "echo ok && rm -rf /"));
        assertTrue(outside.startsWith("ERROR: ") && outside.contains("工作区外"), outside);
    }

    /** 不依赖本机装没装 PowerShell 7：安装与否，&& / || 都必须按语义短路。 */
    @Test
    void chainOperatorsKeepTheirMeaningAndNativeExitCode() {
        RunCommandTool tool = tool();
        String failure = "node -e \"process.exit(4)\"";

        String stopped = tool.execute(Map.of("command", failure + " && " + marker("skipped.txt")));
        assertTrue(stopped.contains("退出码: 4"), stopped);
        assertFalse(Files.exists(workspace.resolve("skipped.txt")), stopped);

        String recovered = tool.execute(Map.of("command", failure + " || " + marker("recovered.txt")));
        assertTrue(recovered.contains("退出码: 0"), recovered);
        assertTrue(Files.exists(workspace.resolve("recovered.txt")), recovered);

        String proceeded = tool.execute(Map.of("command", "node -v && " + marker("proceeded.txt")));
        assertTrue(proceeded.contains("退出码: 0"), proceeded);
        assertTrue(Files.exists(workspace.resolve("proceeded.txt")), proceeded);

        // 左结合：a 失败时 a && b 被跳过、|| c 仍要执行
        String fallback =
            tool.execute(Map.of("command", failure + " && " + marker("never.txt") + " || " + marker("fallback.txt")));
        assertTrue(fallback.contains("退出码: 0"), fallback);
        assertFalse(Files.exists(workspace.resolve("never.txt")), fallback);
        assertTrue(Files.exists(workspace.resolve("fallback.txt")), fallback);
    }

    /** 用 node 写标记文件，避免依赖本机有没有 Unix 的 touch。 */
    private static String marker(String name) {
        return "node -e \"require('fs').writeFileSync('" + name + "','x')\"";
    }

    @Test
    void rejectsUnquotedShellSyntax() {
        RunCommandTool tool = tool();

        for (String command : List.of("git status `whoami`", "echo $(whoami)", "git status &", "(git status)",
            "git log < /etc/passwd", "git status ||", "&& git status")) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.startsWith("ERROR: "), command + " -> " + result);
            assertFalse(result.contains("退出码"), command + " -> " + result);
        }
    }

    @Test
    void ordinaryGitMutationsAreNotExtraConfirm() throws Exception {
        RunCommandTool tool = tool();
        // 单独的空仓库，git 不会顺着父目录找到用户正在用的仓库。
        ProcessBuilder init = new ProcessBuilder("git", "init");
        init.directory(workspace.toFile());
        Assumptions.assumeTrue(init.start().waitFor() == 0, "git init");

        for (String command : List.of("git push origin HEAD:master", "git fetch origin", "git reset --hard HEAD",
            "git clean -fd", "git checkout -- src/Main.java")) {
            assertFalse(tool.confirm(Map.of("command", command)), command);
            assertFalse(tool.readOnly(Map.of("command", command)), command);
            String result = tool.execute(Map.of("command", command));
            assertFalse(result.contains("不在允许范围内"), command + " -> " + result);
            assertFalse(result.contains("仍需人工执行"), command + " -> " + result);
        }
        assertFalse(tool.confirm(Map.of("command", "git status")));
        assertTrue(tool.confirm(Map.of("command", "npm publish")));
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

    /** mvnw 本身也要映射到工作区 wrapper，而不是去 PATH 里找一个不存在的 mvnw。 */
    @Test
    void mapsMvnwToWorkspaceWrapper() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Files.writeString(workspace.resolve("mvnw.cmd"), "@echo wrapper-ok %1\r\n");
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "mvnw -v", "timeoutSeconds", 60));

        assertTrue(result.contains("wrapper-ok"), result);
        assertFalse(result.contains("ERROR: 无法启动命令"), result);
    }

    @Test
    void runsUnixWrapperWithoutExecutablePermission() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path wrapper = workspace.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nprintf 'wrapper-ok:%s\\n' \"$1\"\n");
        assertFalse(Files.isExecutable(wrapper));
        String result = tool().execute(Map.of("command", "mvn -v", "timeoutSeconds", 10));
        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("wrapper-ok:-v"), result);
        String conventional = tool().execute(Map.of("command", "./mvnw -v", "timeoutSeconds", 10));
        assertTrue(conventional.contains("退出码: 0"), conventional);
        assertTrue(conventional.contains("wrapper-ok:-v"), conventional);
    }

    @Test
    void fallsBackToSystemMvnWhenJarWrapperIsMissingWrapperJar() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper = workspace.resolve(win ? "mvnw.cmd" : "mvnw");
        Files.writeString(wrapper, win ? "@echo off\r\nrem MavenWrapperMain maven-wrapper.jar\r\n"
            : "#!/bin/sh\nexec java -cp .mvn/wrapper/maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain \"$@\"\n");
        Path systemBin = Files.createDirectory(workspace.resolve("sys-bin"));
        Path systemMvn = Files.writeString(systemBin.resolve(win ? "mvn.cmd" : "mvn"),
            win ? "@echo system-mvn\r\n" : "#!/bin/sh\necho system-mvn\n");
        if (!win) {
            systemMvn.toFile().setExecutable(true);
        }

        RunCommandTool tool = tool();
        // 1. 缺失 maven-wrapper.jar 且 PATH 有系统 mvn：mvn 不应被改写成会崩溃的 mvnw
        List<String> resolvedMvn = tool.withWrapper(List.of("mvn", "test"), systemBin.toString());
        assertTrue(resolvedMvn.stream().anyMatch(part -> part.endsWith(systemMvn.getFileName().toString())),
            resolvedMvn.toString());
        assertFalse(resolvedMvn.stream().anyMatch(part -> part.contains("mvnw")), resolvedMvn.toString());

        // 2. 用户显式调用 mvnw 时仍尊重用户指定的 wrapper
        List<String> explicitWrapper = tool.withWrapper(List.of("mvnw", "test"), systemBin.toString());
        assertTrue(explicitWrapper.stream().anyMatch(part -> part.contains("mvnw")), explicitWrapper.toString());

        // 3. 补齐 maven-wrapper.jar 后，mvn 恢复优先使用工作区 wrapper
        Files.createDirectories(workspace.resolve(".mvn/wrapper"));
        Files.writeString(workspace.resolve(".mvn/wrapper/maven-wrapper.jar"), "jar");
        List<String> withJar = tool.withWrapper(List.of("mvn", "test"), systemBin.toString());
        assertTrue(withJar.stream().anyMatch(part -> part.contains("mvnw")), withJar.toString());

        // 4. PATH 无系统 mvn 时，即便缺少 jar 也仍保留 wrapper 路径让其按原行为处理
        Files.delete(workspace.resolve(".mvn/wrapper/maven-wrapper.jar"));
        List<String> noSystemMvn = tool.withWrapper(List.of("mvn", "test"), "");
        assertTrue(noSystemMvn.stream().anyMatch(part -> part.contains("mvnw")), noSystemMvn.toString());
    }

    @Test
    void runsWhitelistedCommandInsideWorkspace() {
        RunCommandTool tool = tool();

        String result = tool.execute(Map.of("command", "java -version", "timeoutSeconds", 60));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("耗时:"), result);
        assertFalse(tool.readOnly());
    }

    /** 结果尾部的 Shell 行要说清这次是怎么执行的；单条命令直接启动，不经过 shell。 */
    @Test
    void reportsTheShellActuallyUsed() {
        String direct = tool().execute(Map.of("command", "java -version", "timeoutSeconds", 60));
        assertTrue(direct.contains("Shell: 直接执行"), direct);

        // 串联必然经过 shell：Windows 是 PowerShell，macOS/Linux 是 /bin/sh。
        String chained = tool().execute(Map.of("command", "echo ok && echo ok", "timeoutSeconds", 30));
        String expected = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            ? "Shell: PowerShell" : "Shell: /bin/sh";
        assertTrue(chained.contains(expected), chained);
        assertTrue(chained.contains("退出码: 0"), chained);
    }

    /** Shell 行只是附注：不能顶掉结果首行，也不能把「ERROR: 命令执行被中断」这类前缀挤走。 */
    @Test
    void shellNoteDoesNotDisplaceTheResultHeader() {
        String failed = tool().execute(Map.of("command", "java MissingMain", "timeoutSeconds", 60));
        assertFalse(failed.lines().findFirst().orElse("").contains("Shell:"), failed);
        assertTrue(failed.strip().endsWith("Shell: 直接执行"), failed);
    }

    @Test
    void runsInRequestedChildDirectoryAndRejectsEscapes() throws Exception {
        Path child = Files.createDirectory(workspace.resolve("pilot-web"));
        String result = tool().execute(Map.of("command", "node -p process.cwd()", "workdir", "pilot-web"));
        assertTrue(result.contains("退出码: 0"), result);
        // Windows 上临时目录可能以 8.3 短名出现，比较真实路径而不是字符串
        String printed = result.lines().filter(line -> line.contains("工作目录")).findFirst().orElse("")
            .replaceFirst("^.*工作目录[:：]\\s*", "").strip();
        assertEquals(child.toRealPath(), Path.of(printed).toRealPath(), result);
        assertTrue(
            tool().execute(Map.of("command", "node -v", "workdir", "../outside")).startsWith("ERROR: workdir 无效"));
        assertTrue(
            tool().execute(Map.of("command", "node -v", "workdir", "missing")).startsWith("ERROR: workdir 不是现有目录"));
        assertTrue(tool().execute(Map.of("command", "npm --prefix ../outside test"))
            .startsWith("ERROR: --prefix 必须指向工作区内的目录"));
    }

    @Test
    void resolvesBuildToolFromPath() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path resolved = CommandRuntimeEnvironment.resolveOnPath("mvn", System.getenv("PATH"), windows);

        assertNull(CommandRuntimeEnvironment.resolveOnPath("definitely-not-a-real-binary-xyz", System.getenv("PATH"),
            windows));
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

        String result =
            tool.execute(Map.of("command", "java -XshowSettings:properties -version", "timeoutSeconds", 60));

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
        AgentScope scope = AgentScope.readOnly(List.of(), List.of()).withTools(List.of("run_command"), List.of())
            .withCommands(List.of("git status"), List.of());
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace, scope));

        String result = tool.execute(Map.of("command", "mvn -v"));

        assertTrue(result.startsWith("ERROR: 命令 mvn -v 不在你的命令白名单内"), result);
        assertTrue(result.contains("git status"), result);
    }

    @Test
    void readOnlyScopeRejectsGitWrite() {
        AgentScope scope = AgentScope.readOnly(List.of(), List.of()).withCommands(List.of("git"), List.of());
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace, scope));

        String commit = tool.execute(Map.of("command", "git commit -m \"test\""));
        String push = tool.execute(Map.of("command", "git push"));

        assertTrue(commit.startsWith("ERROR: 只读任务不允许 git commit"), commit);
        assertTrue(push.startsWith("ERROR: 只读任务不允许 git push"), push);
    }

    @Test
    void readOnlyScopeRejectsRedirectAndGitGlobalOptionEscape() {
        AgentScope scope = AgentScope.readOnly(List.of(), List.of()).withCommands(List.of("git"), List.of());
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace, scope));
        assertTrue(tool.execute(Map.of("command", "echo data > output.txt")).contains("只读任务不允许输出重定向写文件"));
        assertTrue(tool.execute(Map.of("command", "git -C /tmp add .")).startsWith("ERROR: "));
        assertTrue(tool.execute(Map.of("command", "git --git-dir=/tmp/.git status")).startsWith("ERROR: "));
    }

    @Test
    void writableScopeStillAllowsGitWriteChecks() {
        AgentScope scope = AgentScope.readWrite(List.of(), List.of()).withCommands(List.of("git commit"), List.of());
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace, scope));

        String result = tool.execute(Map.of("command", "git commit"));

        assertTrue(result.startsWith("ERROR: git commit 必须带 -m"), result);
    }

    @Test
    void tokenizerKeepsQuotedArguments() {
        assertEquals(List.of("git", "commit", "-m", "带 空格 的说明"),
            CommandChain.parse("git commit -m \"带 空格 的说明\"").segments().getFirst().tokens());
        assertEquals(List.of("grep", "-l", "failures=\"[1-9]\\|errors=\"[1-9]"),
            CommandChain.parse("grep -l \"failures=\\\"[1-9]\\|errors=\\\"[1-9]\"").segments().getFirst().tokens());
        assertEquals(List.of("mvn", "-q", "test"),
            CommandChain.parse("  mvn   -q  test  ").segments().getFirst().tokens());
    }

    @Test
    void runsCommandEvenWhenChangeBaselineCannotBeEstablished() throws Exception {
        CommandChangeTracker broken = new CommandChangeTracker() {
            @Override
            public Handle begin(Path root) {
                throw new IllegalStateException("测试锁文件占用");
            }

            @Override
            public Result finish(Handle handle) {
                return Result.empty();
            }
        };
        RunCommandTool tool = new RunCommandTool(new PathValidator(workspace), broken);

        ProcessBuilder init = new ProcessBuilder("git", "init");
        init.directory(workspace.toFile());
        Assumptions.assumeTrue(init.start().waitFor() == 0, "git init");
        String result = tool.execute(Map.of("command", "git add .", "timeoutSeconds", 60));

        assertTrue(result.contains("退出码: 0"), result);
        assertTrue(result.contains("未能建立命令前文件基线"), result);
        assertTrue(result.contains("测试锁文件占用"), result);
    }

    @Test
    void refusesToKillProcessesFromInsideTheAgent() {
        RunCommandTool tool = tool();

        String broad = tool.execute(Map.of("command", "taskkill /F /IM java.exe"));
        String nested = tool.execute(Map.of("command", "node -e \"process.kill(process.pid)\""));

        assertTrue(broad.contains("stop_command"), broad);
        assertTrue(nested.contains("stop_command"), nested);
    }

    @Test
    void detectsNestedOsSandboxFailuresAcrossPlatformsWithoutFalsePositives() {
        String macSeatbelt =
            CommandToolSupport.nestedSandboxHint("sandbox-exec: sandbox_apply: Operation not permitted", 71);
        assertTrue(macSeatbelt.contains("[环境提示]") && macSeatbelt.contains("嵌套 OS 沙箱"), macSeatbelt);

        String macExitOnly = CommandToolSupport.nestedSandboxHint("/usr/bin/sandbox-exec failed", 71);
        assertTrue(macExitOnly.contains("[环境提示]"), macExitOnly);

        String linuxBwrap = CommandToolSupport.nestedSandboxHint("bwrap: No permissions to creating new namespace", 1);
        assertTrue(linuxBwrap.contains("[环境提示]"), linuxBwrap);

        String electronMac = CommandToolSupport.nestedSandboxHint(
            "sandbox initialization failed: Operation not permitted\nFailed to initialize sandbox.", 1);
        assertTrue(electronMac.contains("[环境提示]"), electronMac);

        String electronLinux = CommandToolSupport.nestedSandboxHint(
            "Failed to move to new namespace: PID namespaces supported, but failed: errno = Operation not permitted",
            1);
        assertTrue(electronLinux.contains("[环境提示]"), electronLinux);

        // 普通测试或编译失败不能误报为嵌套沙箱环境问题
        assertEquals("", CommandToolSupport.nestedSandboxHint("Failed to initialize sandbox: invalid flag", 1));
        assertEquals("", CommandToolSupport.nestedSandboxHint("AssertionFailedError: expected <1> but was <2>", 1));
        assertEquals("", CommandToolSupport.nestedSandboxHint("BUILD SUCCESS", 0));
    }

    @Test
    void detectsNestedSandboxFromFreshSurefireReportsOnlyWhenAllFailuresAreSandboxBlocked() throws Exception {
        Path reports = Files.createDirectories(workspace.resolve("pilot-server/target/surefire-reports"));
        Instant startedAt = Instant.now().minusSeconds(1);
        Path sandboxFail = reports.resolve("TEST-com.example.BackgroundCommandRunTest.xml");
        Files.writeString(sandboxFail, """
            <testsuite name="BackgroundCommandRunTest" tests="1" failures="1" errors="0">
              <testcase name="runs"><failure>sandbox-exec: sandbox_apply: Operation not permitted</failure></testcase>
            </testsuite>
            """);

        String detected = CommandToolSupport.nestedSandboxHintFromTestReports(workspace, startedAt);
        assertTrue(detected.startsWith(ToolResults.NESTED_SANDBOX_HINT_PREFIX), detected);

        // 只要同一次运行中混入真实业务失败报告，就绝不能误判成纯环境限制
        Path realFail = reports.resolve("TEST-com.example.BusinessLogicTest.xml");
        Files.writeString(realFail, """
            <testsuite name="BusinessLogicTest" tests="1" failures="1" errors="0">
              <testcase name="calculates"><failure>expected &lt;2&gt; but was &lt;3&gt;</failure></testcase>
            </testsuite>
            """);
        assertEquals("", CommandToolSupport.nestedSandboxHintFromTestReports(workspace, startedAt));

        // 清理真实失败报告，但将时间锚点设为未来，验证旧历史报告不会被误命中
        Files.delete(realFail);
        assertEquals("", CommandToolSupport.nestedSandboxHintFromTestReports(workspace, Instant.now().plusSeconds(30)));
    }

    @Test
    void toolResultsNestedSandboxCheckOnlyInspectsTrustedHeaderNotCommandOutput() {
        String trusted = "隔离：macOS Seatbelt\n" + CommandToolSupport.NESTED_SANDBOX_HINT + "\nERROR: 命令未成功完成\n输出:\nfail";
        assertTrue(ToolResults.isNestedSandboxFailure(trusted));

        // 命令标准输出里出现相同字样不能伪造外层沙箱环境提示
        String spoofed = "隔离：macOS Seatbelt\nERROR: 命令未成功完成\n输出:\n" + CommandToolSupport.NESTED_SANDBOX_HINT;
        assertFalse(ToolResults.isNestedSandboxFailure(spoofed));
    }

    @Test
    void runsInExtraRootWhenWorkdirIsAbsolute() throws IOException {
        Assumptions.assumeTrue(nodeRuns(), "需要 node 才能读出命令真实工作目录");
        Path extra = Files.createDirectory(workspace.resolveSibling("extra-" + UUID.randomUUID()));
        try {
            String result = new RunCommandTool(new PathValidator(workspace, List.of(extra)))
                .execute(Map.of("command", "node -e \"console.log(process.cwd())\"", "workdir", extra.toString()));

            assertFalse(result.startsWith("ERROR"), result);
            assertTrue(result.lines().anyMatch(line -> isSameDirectory(line.strip(), extra)), result);
        } finally {
            Files.deleteIfExists(extra);
        }
    }

    /** 输出的每一行都试一次：只有命令自己打印的工作目录那行能对上，短名和长名都算对上。 */
    private static boolean isSameDirectory(String line, Path expected) {
        if (line.isBlank()) {
            return false;
        }
        try {
            return Files.isSameFile(Path.of(line), expected);
        } catch (InvalidPathException | IOException e) {
            return false;
        }
    }

    private static boolean nodeRuns() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void synchronousOutputIsBoundedAndDrained() throws IOException {
        Path source = workspace.resolve("Burst.java");
        Files.writeString(source, """
            public class Burst {
                public static void main(String[] args) {
                    byte[] block = new byte[8192];
                    java.util.Arrays.fill(block, (byte)'x');
                    for (int i = 0; i < 160; i++) {
                        System.out.write(block, 0, block.length);
                    }
                    System.out.flush();
                }
            }
            """);
        String javaBin = Path
            .of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java")
            .toString();
        String previous = System.getProperty("pilot.os.sandbox");
        System.setProperty("pilot.os.sandbox", "false");
        try {
            String result = tool().execute(Map.of("command", "\"" + javaBin + "\" Burst.java", "timeoutSeconds", 60));
            assertTrue(result.contains("同步执行上限"), result);
            assertTrue(result.contains("退出码: 0"), result);
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.os.sandbox");
            } else {
                System.setProperty("pilot.os.sandbox", previous);
            }
        }
    }

    @Test
    void timeoutDestroysDescendantProcessTree() throws Exception {
        Path pidFile = workspace.resolve("grandchild.pid");
        Path source = workspace.resolve("SpawnTree.java");
        String javaBin = Path
            .of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java")
            .toString();
        Files.writeString(source, """
            import java.nio.file.*;
            public class SpawnTree {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0 && "child".equals(args[0])) {
                        Thread.sleep(60_000L);
                        return;
                    }
                    Process child = new ProcessBuilder(args[0], args[1], "child").start();
                    Files.writeString(Path.of(args[2]), Long.toString(child.pid()));
                    Thread.sleep(60_000L);
                }
            }
            """);
        String previous = System.getProperty("pilot.os.sandbox");
        System.setProperty("pilot.os.sandbox", "false");
        try {
            String command = "\"" + javaBin + "\" SpawnTree.java \"" + javaBin + "\" SpawnTree.java grandchild.pid";
            String result = tool().execute(Map.of("command", command, "timeoutSeconds", 3));
            assertTrue(result.contains("超时") || result.contains("退出码: -1"), result);
            assertTrue(Files.isRegularFile(pidFile), "父进程应在超时前已拉起子进程并写出 grandchild.pid");
            long childPid = Long.parseLong(Files.readString(pidFile).strip());
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline
                && ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(50L);
            }
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "超时终止主进程时必须一并清理子孙进程，残留 PID=" + childPid);
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.os.sandbox");
            } else {
                System.setProperty("pilot.os.sandbox", previous);
            }
        }
    }

    private RunCommandTool tool() {
        return new RunCommandTool(new PathValidator(workspace));
    }
}
