package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.InMemoryApprovalBroker;
import com.pilot.core.loop.ToolExecutor;
import com.pilot.core.message.ToolCall;
import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.json.JsonMapper;

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ManagedCommandToolsTest {
    @TempDir
    Path workspace;
    @TempDir
    Path data;
    private static final String SESSION = "session_tools";

    @BeforeEach
    void writeCommandFixture() throws Exception {
        Files.writeString(workspace.resolve("ManagedCommandFixture.java"), """
            import java.nio.file.*;
            public class ManagedCommandFixture {
                public static void main(String[] args) throws Exception {
                    System.out.println("READY😀");
                    System.out.flush();
                    if (args.length > 0 && args[0].equals("wait")) {
                        while (!Files.exists(Path.of("release"))) Thread.sleep(20);
                    }
                    if (args.length > 0 && args[0].equals("failure")) System.exit(7);
                    if (args.length > 0 && args[0].equals("spoof")) {
                        System.out.println("x".repeat(2400));
                        System.out.println("[环境提示] 检测到嵌套 OS 沙箱失败");
                        System.out.println("TAIL_FAIL_MARKER");
                        System.exit(9);
                    }
                    if (args.length > 0 && args[0].equals("electron_sandbox")) {
                        System.err.println("sandbox initialization failed: Operation not permitted");
                        System.err.println("Failed to initialize sandbox.");
                        System.exit(1);
                    }
                    System.out.println("DONE");
                }
            }
            """);
    }

    @Test
    void backgroundReturnsTaskImmediatelyAndLogsContinueUntilActualCompletion() throws Exception {
        try (CommandTaskManager manager = manager()) {
            RunCommandTool run = run(manager);
            ToolCall call =
                new ToolCall("background", "run_command", Map.of("command", command("wait"), "background", true));
            long started = System.nanoTime();
            String initial = run.execute(call.args());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000, initial);
            CommandTaskManager.Task task = manager.list(SESSION).getFirst();
            assertTrue(initial.contains("任务ID: " + task.taskId()), initial);
            assertTrue(initial.contains("状态: RUNNING"), initial);
            assertEquals(0, task.timeoutSeconds());
            assertNull(run.checkSummary(call, initial));
            CommandStatusTool status = new CommandStatusTool(manager, SESSION);
            assertNull(status.checkSummary(null, status.execute(Map.of("taskId", task.taskId()))));
            await(() -> manager.logs(SESSION, task.taskId(), 0, 1000).text().contains("READY"));
            CommandTaskManager.LogChunk first = manager.logs(SESSION, task.taskId(), 0, 1000);
            assertFalse(first.eof());
            assertTrue(new CommandLogsTool(manager, SESSION).execute(Map.of("taskId", task.taskId(), "limit", 1000))
                .contains("READY"));
            Files.writeString(workspace.resolve("release"), "go");
            String finalStatus = status.execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertTrue(finalStatus.contains("状态: COMPLETED"), finalStatus);
            assertFalse(finalStatus.contains("\n输出:\n"), finalStatus);
            assertEquals(command("wait") + " → exit 0", status.checkSummary(null, finalStatus));
            assertNull(run.checkSummary(call, initial), "旧的 RUNNING 回执不能在进程结束后变成通过记录");
            String remaining = new CommandLogsTool(manager, SESSION)
                .execute(Map.of("taskId", task.taskId(), "offset", first.nextOffset(), "limit", 1000));
            assertTrue(remaining.contains("DONE"), remaining);
            assertFalse(remaining.contains("READY"), remaining);
            assertTrue(remaining.contains("eof: true"), remaining);
        }
    }

    @Test
    void managedForegroundExposesLiveTaskAndInterruptionStopsItsProcess() throws Exception {
        try (CommandTaskManager manager = manager()) {
            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    result.set(run(manager).execute(Map.of("command", command("wait"))));
                } finally {
                    done.countDown();
                }
            });
            try {
                await(() -> !manager.list(SESSION).isEmpty());
                CommandTaskManager.Task task = manager.list(SESSION).getFirst();
                assertFalse(task.background());
                assertEquals(120, task.timeoutSeconds());
                await(() -> manager.logs(SESSION, task.taskId(), 0, 1000).text().contains("READY"));
                assertEquals(1, done.getCount(), "前台命令必须等到退出才返回");
                caller.interrupt();
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertTrue(result.get().startsWith("ERROR: 命令执行被中断"), result.get());
                await(() -> !CommandToolSupport.running(manager.get(SESSION, task.taskId())));
                CommandTaskManager.Task stopped = manager.get(SESSION, task.taskId());
                assertEquals("CANCELLED", stopped.status());
                assertNotNull(stopped.pid(), "运行过的命令必须记录真实 pid，否则无法管理进程树");
                assertFalse(ProcessHandle.of(stopped.pid()).map(ProcessHandle::isAlive).orElse(false));
            } finally {
                caller.interrupt();
            }
        }
    }

    @Test
    void foregroundSuccessAndBackgroundFailureRecordRealExitCodes() {
        try (CommandTaskManager manager = manager()) {
            RunCommandTool run = run(manager);
            ToolCall call = new ToolCall("foreground", "run_command", Map.of("command", "java -version"));
            String result = run.execute(call.args());
            assertTrue(result.contains("任务ID:"), result);
            assertTrue(result.contains("退出码: 0"), result);
            assertTrue(result.contains("输出:"), result);
            assertEquals("java -version → exit 0", run.checkSummary(call, result));
            run.execute(Map.of("command", command("failure"), "background", true));
            CommandTaskManager.Task failed = manager.list(SESSION).stream()
                .filter(task -> task.command().endsWith("failure")).findFirst().orElseThrow();
            CommandStatusTool status = new CommandStatusTool(manager, SESSION);
            String failure = status.execute(Map.of("taskId", failed.taskId(), "waitSeconds", 5));
            assertTrue(failure.startsWith("ERROR:"), failure);
            assertTrue(failure.contains("状态: FAILED"), failure);
            assertTrue(failure.contains("\n输出:\n") && failure.contains("READY"), failure);
            assertEquals(command("failure") + " → exit 7", status.checkSummary(null, failure));
        }
    }

    @Test
    void failedBackgroundStatusIncludesBoundedLogTailAndDetectsCrossSessionNestedSandbox() {
        try (CommandTaskManager manager = manager()) {
            RunCommandTool ownerRun = new RunCommandTool(new PathValidator(workspace), manager, "session_owner");
            ownerRun.execute(Map.of("command", command("spoof"), "background", true));
            CommandTaskManager.Task spoofTask = manager.list("session_owner").stream()
                .filter(task -> task.command().endsWith("spoof")).findFirst().orElseThrow();
            CommandStatusTool observer = new CommandStatusTool(manager, SESSION);
            String spoofResult = observer
                .execute(Map.of("taskId", spoofTask.taskId(), "waitSeconds", 5, "ownerSessionId", "session_owner"));
            assertTrue(spoofResult.startsWith("ERROR:"), spoofResult);
            assertTrue(spoofResult.contains("\n输出:\n…（已省略前 "), spoofResult);
            assertTrue(spoofResult.contains("TAIL_FAIL_MARKER"), spoofResult);
            assertFalse(ToolResults.isNestedSandboxFailure(spoofResult), "失败尾部输出不能伪造头部沙箱环境提示");

            ownerRun.execute(Map.of("command", command("electron_sandbox"), "background", true));
            CommandTaskManager.Task sandboxTask = manager.list("session_owner").stream()
                .filter(task -> task.command().endsWith("electron_sandbox")).findFirst().orElseThrow();
            String sandboxResult = observer
                .execute(Map.of("taskId", sandboxTask.taskId(), "waitSeconds", 5, "ownerSessionId", "session_owner"));
            assertTrue(ToolResults.isNestedSandboxFailure(sandboxResult), sandboxResult);
            assertTrue(sandboxResult.contains("\n输出:\n") && sandboxResult.contains("Failed to initialize sandbox."),
                sandboxResult);
        }
    }

    @Test
    void silentForegroundCommandMovesToBackgroundWithoutBeingKilled() throws Exception {
        try (CommandTaskManager manager = manager()) {
            RunCommandTool run = run(manager);
            run.silenceLimitMs = 400;
            long started = System.nanoTime();
            String result = run.execute(Map.of("command", command("wait"), "timeoutSeconds", 30));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(tookMs < 5_000, result);
            assertTrue(result.contains("已转到后台"), result);
            assertTrue(result.contains("没有新输出"), result);
            CommandTaskManager.Task task = manager.list(SESSION).getFirst();
            assertTrue(task.background(), result);
            assertEquals("RUNNING", task.status());
            Files.writeString(workspace.resolve("release"), "go");
            String done =
                new CommandStatusTool(manager, SESSION).execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertTrue(done.contains("状态: COMPLETED"), done);
        }
    }

    @Test
    void ordinaryNonZeroExitIsNotDescribedAsABadInvocation() {
        try (CommandTaskManager manager = manager()) {
            // exit 7 属于程序自身的失败（测试、lint、grep 没命中同理），只有 2/126/127 才提示调用方式可能有误
            String result = run(manager).execute(Map.of("command", command("failure")));
            assertFalse(result.contains("调用方式不对"), result);
            assertTrue(result.contains("状态: FAILED"), result);
        }
    }

    @Test
    void statusReturnsWithinTheBudgetWhenChangeTrackingIsSlow() throws Exception {
        class SlowChanges implements CommandChangeTracker {
            @Override
            public Handle begin(Path root) {
                return new Handle("run", "slow");
            }

            @Override
            public Result finish(Handle handle) {
                try {
                    Thread.sleep(8_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.empty();
            }
        }
        try (CommandTaskManager manager = manager()) {
            RunCommandTool run = new RunCommandTool(new PathValidator(workspace), manager, SESSION, new SlowChanges());
            run.execute(Map.of("command", command("wait"), "background", true));
            Files.writeString(workspace.resolve("release"), "go");
            CommandTaskManager.Task task = manager.list(SESSION).getFirst();
            long started = System.nanoTime();
            String result = new CommandStatusTool(manager, SESSION, new SlowChanges())
                .execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(tookMs < 7_000, result);
            assertTrue(result.contains("变更核对超过 5 秒"), result);
            assertTrue(result.contains("状态: COMPLETED") || result.contains("状态: RUNNING"), result);
        }
    }

    @Test
    void explicitBackgroundTimeoutIsHonoredAndCannotPassVerification() {
        try (CommandTaskManager manager = manager()) {
            run(manager).execute(Map.of("command", command("wait"), "background", true, "timeoutSeconds", 1));
            CommandTaskManager.Task task = manager.list(SESSION).getFirst();
            assertEquals(1, task.timeoutSeconds());
            CommandStatusTool status = new CommandStatusTool(manager, SESSION);
            String result = status.execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertTrue(result.contains("状态: TIMED_OUT"), result);
            String check = status.checkSummary(null, result);
            assertTrue(check == null || !check.contains("exit 0"), String.valueOf(check));
        }
    }

    @Test
    void securityValidationAndAgentScopeApplyBeforeBothExecutionModes() {
        try (CommandTaskManager manager = manager()) {
            RunCommandTool legacy = new RunCommandTool(new PathValidator(workspace));
            RunCommandTool managed = run(manager);
            // 危险命令与非法 shell 替换语法在三种执行模式启动前都必须统一拒绝（绝对路径命令已按 10a7b2f3 允许，见 RunCommandToolTest）
            for (String command : List.of("rm -rf /", "sudo java -version", "echo $(whoami)", "git status && rm -rf /",
                "git add ../outside", "git commit")) {
                String expected = legacy.execute(Map.of("command", command));
                assertTrue(expected.startsWith("ERROR:"), expected);
                assertEquals(expected, managed.execute(Map.of("command", command, "background", true)));
                assertEquals(expected, managed.execute(Map.of("command", command)));
            }
            assertFalse(legacy.confirm(Map.of("command", "git reset --hard HEAD")));
            assertFalse(managed.confirm(Map.of("command", "git reset --hard HEAD", "background", true)));
            assertTrue(legacy.confirm(Map.of("command", "npm publish")));
            AgentScope scope = AgentScope.readOnly(List.of(), List.of()).withCommands(List.of("git status"), List.of());
            RunCommandTool scoped = new RunCommandTool(new PathValidator(workspace, scope), manager, SESSION);
            assertTrue(scoped.execute(Map.of("command", "java -version", "background", true))
                .startsWith("ERROR: 命令 java -version 不在你的命令白名单内"));
            assertTrue(manager.list(SESSION).isEmpty());
            assertTrue(legacy.execute(Map.of("command", "java -version", "background", true)).contains("不支持后台命令"));
        }
    }

    @Test
    void readOnlyAndApprovalPoliciesTreatBackgroundAsCommandAndQueriesAsReads() {
        try (CommandTaskManager manager = manager()) {
            RunCommandTool run = run(manager);
            StopCommandTool stop = new StopCommandTool(manager, SESSION);
            CommandStatusTool status = new CommandStatusTool(manager, SESSION);
            CommandLogsTool logs = new CommandLogsTool(manager, SESSION);
            ToolCall call =
                new ToolCall("command", "run_command", Map.of("command", "java -version", "background", true));
            for (PermissionMode mode : PermissionMode.values()) {
                PolicyPermissionGate policy = new PolicyPermissionGate(mode);
                PermissionDecision expected = switch (mode) {
                    case READ_ONLY -> PermissionDecision.DENY;
                    case ASK, AUTO_EDIT -> PermissionDecision.ASK;
                    case AUTO -> PermissionDecision.ALLOW;
                };
                assertEquals(expected, policy.check(call, run).decision());
                assertEquals(expected, policy.check(call, stop).decision());
                assertEquals(PermissionDecision.ALLOW, policy.check(call, status).decision());
                assertEquals(PermissionDecision.ALLOW, policy.check(call, logs).decision());
            }
            ToolRegistry registry = new ToolRegistry();
            registry.register(run);
            ToolExecutor executor = new ToolExecutor(registry, new PolicyPermissionGate(PermissionMode.READ_ONLY),
                new InMemoryApprovalBroker(Duration.ofSeconds(1)));
            ToolExecutor.Outcome denied = executor.execute(call, ignored -> {
            });
            assertTrue(denied.result().startsWith("ERROR: 权限拒绝"));
            assertNull(denied.check());
            assertTrue(manager.list(SESSION).isEmpty());
        }
    }

    @Test
    void queriesAndStopAreSessionScopedAndArgumentsAreBounded() throws Exception {
        try (CommandTaskManager manager = manager()) {
            run(manager).execute(Map.of("command", command("wait"), "background", true, "timeoutSeconds", 900));
            CommandTaskManager.Task task = manager.list(SESSION).getFirst();
            assertEquals(600, task.timeoutSeconds());
            for (Tool tool : List.of(new CommandStatusTool(manager, "other"), new CommandLogsTool(manager, "other"),
                new StopCommandTool(manager, "other"))) {
                assertTrue(tool.execute(Map.of("taskId", task.taskId())).startsWith("ERROR:"));
            }
            assertTrue(CommandToolSupport.running(manager.get(SESSION, task.taskId())));
            CommandStatusTool status = new CommandStatusTool(manager, SESSION);
            CommandLogsTool logs = new CommandLogsTool(manager, SESSION);
            assertTrue(status.execute(Map.of("taskId", task.taskId(), "waitSeconds", 61)).startsWith("ERROR:"));
            assertTrue(logs.execute(Map.of("taskId", task.taskId(), "offset", -1)).startsWith("ERROR:"));
            assertTrue(logs.execute(Map.of("taskId", task.taskId(), "limit", 32001)).startsWith("ERROR:"));
            new StopCommandTool(manager, SESSION).execute(Map.of("taskId", task.taskId()));
            await(() -> !CommandToolSupport.running(manager.get(SESSION, task.taskId())));
            assertEquals("CANCELLED", manager.get(SESSION, task.taskId()).status());
        }
    }

    @Test
    void backgroundBuildUsesUnixWrapperWithoutExecutablePermission() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path wrapper = workspace.resolve("gradlew");
        Files.writeString(wrapper, "#!/bin/sh\nprintf 'wrapper-build:%s\\n' \"$1\"\n");
        assertFalse(Files.isExecutable(wrapper));
        try (CommandTaskManager manager = manager()) {
            String initial = run(manager).execute(Map.of("command", "gradle test", "background", true));
            CommandTaskManager.Task task = manager.list(SESSION).getFirst();
            assertTrue(initial.contains("任务ID: " + task.taskId()), initial);
            String result =
                new CommandStatusTool(manager, SESSION).execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertTrue(result.contains("状态: COMPLETED"), result);
            assertEquals(0, manager.get(SESSION, task.taskId()).exitCode());
            assertTrue(manager.logs(SESSION, task.taskId(), 0, 1000).text().contains("wrapper-build:test"));
        }
    }

    @Test
    void foregroundAndTerminalBackgroundResultsReturnStructuredCommandChanges() {
        class FakeChanges implements CommandChangeTracker {
            final AtomicInteger begins = new AtomicInteger();
            final AtomicInteger finishes = new AtomicInteger();

            @Override
            public Handle begin(Path root) {
                begins.incrementAndGet();
                return new Handle("run", "command_123");
            }

            @Override
            public Result finish(Handle handle) {
                finishes.incrementAndGet();
                return new Result(List.of("src/Generated.java"), true, "");
            }
        }
        FakeChanges changes = new FakeChanges();
        try (CommandTaskManager manager = manager()) {
            RunCommandTool run = new RunCommandTool(new PathValidator(workspace), manager, SESSION, changes);
            ToolResult foreground = run.executeResult(Map.of("command", "java ManagedCommandFixture.java"));
            assertEquals(List.of("src/Generated.java"), foreground.changedPaths());

            ToolResult launched = run.executeResult(Map.of("command", command("wait"), "background", true));
            assertTrue(launched.changedPaths().isEmpty(), "running command has no terminal diff yet");
            CommandTaskManager.Task task = manager.list(SESSION).getFirst();
            assertNotNull(task.changeTracking());
            try {
                Files.writeString(workspace.resolve("release"), "go");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ToolResult terminal = new CommandStatusTool(manager, SESSION, changes)
                .executeResult(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertEquals(List.of("src/Generated.java"), terminal.changedPaths());
            assertEquals(2, changes.begins.get());
            assertEquals(2, changes.finishes.get());
        }
    }

    @Test
    void listsAndTakesOverTasksFromAnotherSessionByOwner() throws Exception {
        try (CommandTaskManager manager = manager()) {
            RunCommandTool ownerRun = new RunCommandTool(new PathValidator(workspace), manager, "session_owner");
            String launched = ownerRun.execute(Map.of("command", command("wait"), "background", true));
            CommandTaskManager.Task task = manager.list("session_owner").getFirst();
            assertTrue(launched.contains("任务ID: " + task.taskId()), launched);

            // 新会话默认看不到旧会话的任务，但 command_tasks 能列出来
            assertNull(manager.get(SESSION, task.taskId()));
            String listed = new CommandTasksTool(manager).execute(Map.of());
            assertTrue(listed.contains(task.taskId()), listed);
            assertTrue(listed.contains("session_owner"), listed);
            assertTrue(listed.contains("RUNNING"), listed);

            // 带上归属会话才能读状态与日志
            CommandStatusTool status = new CommandStatusTool(manager, SESSION);
            assertTrue(status.execute(Map.of("taskId", task.taskId())).startsWith("ERROR"), "未带 owner 时不应读到别人的任务");
            assertTrue(status.execute(Map.of("taskId", task.taskId(), "ownerSessionId", "session_owner"))
                .contains("状态: RUNNING"));
            await(() -> manager.logs("session_owner", task.taskId(), 0, 1000).text().contains("READY"));
            String logs = new CommandLogsTool(manager, SESSION)
                .execute(Map.of("taskId", task.taskId(), "limit", 1000, "ownerSessionId", "session_owner"));
            assertTrue(logs.contains("READY"), logs);

            // 带上归属会话也能停掉
            new StopCommandTool(manager, SESSION)
                .execute(Map.of("taskId", task.taskId(), "ownerSessionId", "session_owner"));
            await(() -> manager.get("session_owner", task.taskId()).status().equals("CANCELLED"));
            String running = new CommandTasksTool(manager).execute(Map.of("includeFinished", false));
            assertTrue(running.contains("共 0 条") || !running.contains(task.taskId()), running);
        }
    }

    private CommandTaskManager manager() {
        return new CommandTaskManager(data, JsonMapper.builder().build());
    }

    private RunCommandTool run(CommandTaskManager manager) {
        return new RunCommandTool(new PathValidator(workspace), manager, SESSION);
    }

    private static String command(String mode) {
        return "java ManagedCommandFixture.java " + mode;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "等待命令状态或日志超时");
    }
}
