package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.exec.ExecutionBackend;
import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.exec.ExecutionIsolation;
import com.pilot.core.exec.ExecutionRequest;
import com.pilot.core.exec.ExecutionResult;
import com.pilot.core.exec.ExecutionSettings;
import com.pilot.core.hook.Hooks;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PermissionResult;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.file.CreateFileTool;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.PathValidator;

class HookPermissionsTest {
    @TempDir
    Path workspace;
    @TempDir
    Path userHome;

    private Hooks hooks(String event, boolean userConfig) throws Exception {
        Path file = (userConfig ? userHome : workspace).resolve(".pilot/settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"hooks\":{\"" + event
            + "\":[{\"matcher\":\"read_file\",\"command\":\"echo changed > hook-marker.txt\"}]}}");
        Files.writeString(workspace.resolve("input.txt"), "read-only input");
        return Hooks.load(workspace, userHome);
    }

    private ToolExecutor executor(PolicyPermissionGate gate, ApprovalBroker approval, Hooks hooks) {
        var tools = new ToolRegistry();
        tools.register(new ReadFileTool(new PathValidator(workspace)));
        return new ToolExecutor(tools, gate, approval).withHooks(hooks);
    }

    private ToolExecutor.Outcome read(ToolExecutor executor) {
        return executor.execute(new ToolCall("read", "read_file", Map.of("path", "input.txt")), event -> {
        });
    }

    @Test
    void readOnlyCannotWriteThroughWorkspaceOrUserHook() throws Exception {
        for (boolean user : List.of(false, true)) {
            var approvals = new AtomicInteger();
            var result = read(executor(new PolicyPermissionGate(PermissionMode.READ_ONLY), (id, name, args) -> {
                approvals.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }, hooks("PostToolUse", user)));
            assertTrue(result.result().contains("read-only input"));
            assertTrue(result.result().contains("READ_ONLY"));
            assertEquals(0, approvals.get());
            assertFalse(Files.exists(workspace.resolve("hook-marker.txt")));
        }
    }

    @Test
    void workspaceHookRequiresActionApprovalEvenUnderAuto() throws Exception {
        var approvals = new AtomicInteger();
        var configuration = hooks("PostToolUse", false);
        read(executor(new PolicyPermissionGate(PermissionMode.AUTO), (id, name, args) -> {
            approvals.incrementAndGet();
            assertTrue(args.contains("settings.json"));
            assertTrue(args.contains("echo changed > hook-marker.txt"));
            return CompletableFuture.completedFuture(false);
        }, configuration));
        assertEquals(1, approvals.get());
        assertFalse(Files.exists(workspace.resolve("hook-marker.txt")));
        read(executor(new PolicyPermissionGate(PermissionMode.AUTO),
            (id, name, args) -> CompletableFuture.completedFuture(true), configuration));
        assertTrue(Files.readString(workspace.resolve("hook-marker.txt")).contains("changed"));
    }

    @Test
    void commandPermissionStillAppliesToUserConfiguration() throws Exception {
        read(executor(new PolicyPermissionGate(PermissionMode.AUTO_EDIT), ApprovalBroker.denyAll(),
            hooks("PostToolUse", true)));
        assertFalse(Files.exists(workspace.resolve("hook-marker.txt")));
    }

    @Test
    void deniedPreHookStopsToolAndDirectHookEntryCannotExecute() throws Exception {
        var configuration = hooks("PreToolUse", false);
        var result =
            read(executor(new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(), configuration));
        assertFalse(result.usableEvidence());
        assertTrue(result.result().startsWith("ERROR: PreToolUse"));
        assertTrue(configuration.run(Hooks.Event.PRE_TOOL_USE, "read_file").blocked());
        assertFalse(Files.exists(workspace.resolve("hook-marker.txt")));
    }

    @Test
    void permissionChangeDuringApprovalPreventsHookExecution() throws Exception {
        var mode = new AtomicReference<>(PermissionMode.AUTO);
        var result = read(executor(new PolicyPermissionGate(mode::get), (id, name, args) -> {
            mode.set(PermissionMode.READ_ONLY);
            return CompletableFuture.completedFuture(true);
        }, hooks("PostToolUse", false)));
        assertTrue(result.result().contains("READ_ONLY"));
        assertFalse(Files.exists(workspace.resolve("hook-marker.txt")));
    }

    @Test
    void degradedIsolationCannotFallBackAndSelectedBackendReceivesLimits() throws Exception {
        var configuration = hooks("PostToolUse", false);
        var captured = new AtomicReference<ExecutionRequest>();
        ExecutionBackend backend = new ExecutionBackend() {
            public String id() {
                return "fixture";
            }

            public ExecutionIsolation isolation() {
                return ExecutionIsolation.CONTAINER;
            }

            public boolean available() {
                return true;
            }

            public String unavailableReason() {
                return "";
            }

            public ExecutionResult run(ExecutionRequest request) {
                captured.set(request);
                return new ExecutionResult(0, "isolated", false, false, id(), isolation(), "");
            }

            public List<String> describe(ExecutionRequest request) {
                return List.of();
            }
        };
        var tool = executor(new PolicyPermissionGate(PermissionMode.AUTO),
            (id, name, args) -> CompletableFuture.completedFuture(true), configuration);
        tool.withHookExecution(new ExecutionBackends.Selection(backend, ExecutionIsolation.CONTAINER, true,
            "missing image", ExecutionSettings.local()));
        assertTrue(read(tool).result().contains("隔离不可用"));
        assertNull(captured.get());
        tool.withHookExecution(new ExecutionBackends.Selection(backend, ExecutionIsolation.CONTAINER, false, "",
            ExecutionSettings.local()));
        assertTrue(read(tool).result().contains("isolated"));
        assertEquals(workspace.toAbsolutePath(), captured.get().workspaceRoot());
        assertFalse(captured.get().network());
        assertTrue(captured.get().limits().timeoutSeconds() <= 60);
        assertTrue(captured.get().limits().maxOutputBytes() <= 32000);
        assertFalse(Files.exists(workspace.resolve("hook-marker.txt")));
    }

    @Test
    void approvedWriteStillRunsPreHookAndCommandDenyRuleCannotBeBypassed() throws Exception {
        Path settings = workspace.resolve(".pilot/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings,
            "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"create_file\",\"command\":\"exit 1\",\"blockOnFailure\":true}]}}");
        var tools = new ToolRegistry();
        tools.register(new CreateFileTool(new PathValidator(workspace)));
        var approvals = new AtomicInteger();
        var executor = new ToolExecutor(tools, new PolicyPermissionGate(PermissionMode.ASK), (id, name, args) -> {
            approvals.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }).withHooks(Hooks.load(workspace, userHome));
        var result = executor
            .execute(new ToolCall("write", "create_file", Map.of("path", "output.txt", "content", "x")), event -> {
            });
        assertEquals(2, approvals.get());
        assertTrue(result.result().startsWith("ERROR: PreToolUse"));
        assertFalse(Files.exists(workspace.resolve("output.txt")));
        var readTools = new ToolRegistry();
        readTools.register(new ReadFileTool(new PathValidator(workspace)));
        var forbidden = new ToolExecutor(readTools, (call, tool) -> "run_command".equals(call.name())
            ? PermissionResult.deny("命令禁止") : PermissionResult.allow(), (id, name, args) -> {
                throw new AssertionError("拒绝的命令不能再请求审批");
            }).withHooks(hooks("PostToolUse", false));
        assertTrue(read(forbidden).result().contains("命令禁止"));
        assertFalse(Files.exists(workspace.resolve("hook-marker.txt")));
    }
}
