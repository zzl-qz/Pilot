package com.pilot.core.tool;

import com.pilot.core.approval.InMemoryApprovalBroker;
import com.pilot.core.multi.AgentScope;
import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ManagedCommandToolsTest {
    @TempDir Path workspace;
    @TempDir Path data;
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
                        System.out.println("DONE");
                    }
                }
                """);
    }

    @Test
    void backgroundReturnsTaskImmediatelyAndLogsContinueUntilActualCompletion() throws Exception {
        try (var manager = manager()) {
            var run = run(manager);
            var call = new ToolCall("background", "run_command", Map.of("command", command("wait"), "background", true));
            long started = System.nanoTime();
            String initial = run.execute(call.args());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000, initial);
            var task = manager.list(SESSION).getFirst();
            assertTrue(initial.contains("任务ID: " + task.taskId()), initial);
            assertTrue(initial.contains("状态: RUNNING"), initial);
            assertEquals(0, task.timeoutSeconds());
            assertNull(run.checkSummary(call, initial));
            var status = new CommandStatusTool(manager, SESSION);
            assertNull(status.checkSummary(null, status.execute(Map.of("taskId", task.taskId()))));
            await(() -> manager.logs(SESSION, task.taskId(), 0, 1000).text().contains("READY"));
            var first = manager.logs(SESSION, task.taskId(), 0, 1000);
            assertFalse(first.eof());
            assertTrue(new CommandLogsTool(manager, SESSION).execute(Map.of("taskId", task.taskId(), "limit", 1000))
                    .contains("READY"));
            Files.writeString(workspace.resolve("release"), "go");
            String finalStatus = status.execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertTrue(finalStatus.contains("状态: COMPLETED"), finalStatus);
            assertEquals(command("wait") + " → exit 0", status.checkSummary(null, finalStatus));
            assertNull(run.checkSummary(call, initial), "旧的 RUNNING 回执不能在进程结束后变成通过记录");
            String remaining = new CommandLogsTool(manager, SESSION).execute(
                    Map.of("taskId", task.taskId(), "offset", first.nextOffset(), "limit", 1000));
            assertTrue(remaining.contains("DONE"), remaining);
            assertFalse(remaining.contains("READY"), remaining);
            assertTrue(remaining.contains("eof: true"), remaining);
        }
    }

    @Test
    void managedForegroundExposesLiveTaskAndInterruptionStopsItsProcess() throws Exception {
        try (var manager = manager()) {
            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread caller = Thread.ofVirtual().start(() -> {
                try { result.set(run(manager).execute(Map.of("command", command("wait")))); }
                finally { done.countDown(); }
            });
            try {
                await(() -> !manager.list(SESSION).isEmpty());
                var task = manager.list(SESSION).getFirst();
                assertFalse(task.background());
                assertEquals(120, task.timeoutSeconds());
                await(() -> manager.logs(SESSION, task.taskId(), 0, 1000).text().contains("READY"));
                assertEquals(1, done.getCount(), "前台命令必须等到退出才返回");
                caller.interrupt();
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertTrue(result.get().startsWith("ERROR: 命令执行被中断"), result.get());
                await(() -> !CommandToolSupport.running(manager.get(SESSION, task.taskId())));
                assertEquals("CANCELLED", manager.get(SESSION, task.taskId()).status());
                assertFalse(ProcessHandle.of(task.pid()).map(ProcessHandle::isAlive).orElse(false));
            } finally { caller.interrupt(); }
        }
    }

    @Test
    void foregroundSuccessAndBackgroundFailureRecordRealExitCodes() {
        try (var manager = manager()) {
            var run = run(manager);
            var call = new ToolCall("foreground", "run_command", Map.of("command", "java -version"));
            String result = run.execute(call.args());
            assertTrue(result.contains("任务ID:"), result);
            assertTrue(result.contains("退出码: 0"), result);
            assertTrue(result.contains("输出:"), result);
            assertEquals("java -version → exit 0", run.checkSummary(call, result));
            run.execute(Map.of("command", command("failure"), "background", true));
            var failed = manager.list(SESSION).stream().filter(task -> task.command().endsWith("failure")).findFirst().orElseThrow();
            var status = new CommandStatusTool(manager, SESSION);
            String failure = status.execute(Map.of("taskId", failed.taskId(), "waitSeconds", 5));
            assertTrue(failure.startsWith("ERROR:"), failure);
            assertTrue(failure.contains("状态: FAILED"), failure);
            assertEquals(command("failure") + " → exit 7", status.checkSummary(null, failure));
        }
    }

    @Test
    void explicitBackgroundTimeoutIsHonoredAndCannotPassVerification() {
        try (var manager = manager()) {
            run(manager).execute(Map.of("command", command("wait"), "background", true, "timeoutSeconds", 1));
            var task = manager.list(SESSION).getFirst();
            assertEquals(1, task.timeoutSeconds());
            var status = new CommandStatusTool(manager, SESSION);
            String result = status.execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertTrue(result.contains("状态: TIMED_OUT"), result);
            String check = status.checkSummary(null, result);
            assertTrue(check == null || !check.contains("exit 0"), String.valueOf(check));
        }
    }

    @Test
    void securityValidationAndAgentScopeApplyBeforeBothExecutionModes() {
        try (var manager = manager()) {
            var legacy = new RunCommandTool(new PathValidator(workspace));
            var managed = run(manager);
            for (String command : List.of("rm -rf /", "/bin/java -version", "git status && java -version",
                    "git reset --hard HEAD", "git add ../outside", "git commit")) {
                String expected = legacy.execute(Map.of("command", command));
                assertTrue(expected.startsWith("ERROR:"), expected);
                assertEquals(expected, managed.execute(Map.of("command", command, "background", true)));
                assertEquals(expected, managed.execute(Map.of("command", command)));
            }
            var scope = AgentScope.readOnly(List.of(), List.of()).withCommands(List.of("git status"), List.of());
            var scoped = new RunCommandTool(new PathValidator(workspace, scope), manager, SESSION);
            assertTrue(scoped.execute(Map.of("command", "java -version", "background", true))
                    .startsWith("ERROR: 命令 java -version 不在你的命令白名单内"));
            assertTrue(manager.list(SESSION).isEmpty());
            assertTrue(legacy.execute(Map.of("command", "java -version", "background", true))
                    .contains("不支持后台命令"));
        }
    }

    @Test
    void readOnlyAndApprovalPoliciesTreatBackgroundAsCommandAndQueriesAsReads() {
        try (var manager = manager()) {
            var run = run(manager);
            var stop = new StopCommandTool(manager, SESSION);
            var status = new CommandStatusTool(manager, SESSION);
            var logs = new CommandLogsTool(manager, SESSION);
            var call = new ToolCall("command", "run_command", Map.of("command", "java -version", "background", true));
            for (PermissionMode mode : PermissionMode.values()) {
                var policy = new PolicyPermissionGate(mode);
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
            var registry = new ToolRegistry();
            registry.register(run);
            var executor = new ToolExecutor(registry, new PolicyPermissionGate(PermissionMode.READ_ONLY),
                    new InMemoryApprovalBroker(Duration.ofSeconds(1)));
            var denied = executor.execute(call, ignored -> { });
            assertTrue(denied.result().startsWith("ERROR: 权限拒绝"));
            assertNull(denied.check());
            assertTrue(manager.list(SESSION).isEmpty());
        }
    }

    @Test
    void queriesAndStopAreSessionScopedAndArgumentsAreBounded() throws Exception {
        try (var manager = manager()) {
            run(manager).execute(Map.of("command", command("wait"), "background", true, "timeoutSeconds", 900));
            var task = manager.list(SESSION).getFirst();
            assertEquals(600, task.timeoutSeconds());
            for (Tool tool : List.of(new CommandStatusTool(manager, "other"), new CommandLogsTool(manager, "other"),
                    new StopCommandTool(manager, "other"))) {
                assertTrue(tool.execute(Map.of("taskId", task.taskId())).startsWith("ERROR:"));
            }
            assertTrue(CommandToolSupport.running(manager.get(SESSION, task.taskId())));
            var status = new CommandStatusTool(manager, SESSION);
            var logs = new CommandLogsTool(manager, SESSION);
            assertTrue(status.execute(Map.of("taskId", task.taskId(), "waitSeconds", 6)).startsWith("ERROR:"));
            assertTrue(logs.execute(Map.of("taskId", task.taskId(), "offset", -1)).startsWith("ERROR:"));
            assertTrue(logs.execute(Map.of("taskId", task.taskId(), "limit", 32001)).startsWith("ERROR:"));
            new StopCommandTool(manager, SESSION).execute(Map.of("taskId", task.taskId()));
            await(() -> !CommandToolSupport.running(manager.get(SESSION, task.taskId())));
            assertEquals("CANCELLED", manager.get(SESSION, task.taskId()).status());
        }
    }

    @Test
    void backgroundBuildUsesUnixWrapperWithoutExecutablePermission() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path wrapper = workspace.resolve("gradlew");
        Files.writeString(wrapper, "#!/bin/sh\nprintf 'wrapper-build:%s\\n' \"$1\"\n");
        assertFalse(Files.isExecutable(wrapper));
        try (var manager = manager()) {
            String initial = run(manager).execute(Map.of("command", "gradle test", "background", true));
            var task = manager.list(SESSION).getFirst();
            assertTrue(initial.contains("任务ID: " + task.taskId()), initial);
            String result = new CommandStatusTool(manager, SESSION).execute(Map.of("taskId", task.taskId(), "waitSeconds", 5));
            assertTrue(result.contains("状态: COMPLETED"), result);
            assertEquals(0, manager.get(SESSION, task.taskId()).exitCode());
            assertTrue(manager.logs(SESSION, task.taskId(), 0, 1000).text().contains("wrapper-build:test"));
        }
    }

    private CommandTaskManager manager() { return new CommandTaskManager(data, JsonMapper.builder().build()); }
    private RunCommandTool run(CommandTaskManager manager) { return new RunCommandTool(new PathValidator(workspace), manager, SESSION); }
    private static String command(String mode) { return "java ManagedCommandFixture.java " + mode; }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "等待命令状态或日志超时");
    }
}
