package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;
import com.pilot.core.tool.ToolRegistry;

import tools.jackson.databind.ObjectMapper;

class McpPermissionsTest {
    @TempDir
    Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> config(Map<String, Object> permissions) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return Map.of(
            "command", java.toString(), "args", List.of("-cp", System.getProperty("java.class.path"),
                McpProcessFixture.class.getName(), "permissions", root.resolve("ready").toString()),
            "pilotPermissions", permissions);
    }

    private void write(Path file, Map<String, Object> permissions) throws Exception {
        Files.writeString(file, mapper.writeValueAsString(Map.of("mcpServers", Map.of("fake", config(permissions)))));
    }

    @Test
    void serverSelfReportedReadOnlyCannotBypassLocalPermissionMode() throws Exception {
        write(root.resolve(".mcp.json"), Map.of("trustReadOnlyHints", true));
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            manager.tools(root, "fake");
            var tool = new McpCallTool(manager, root);
            var call = new ToolCall("one", "mcp_call", Map.of("server", "fake", "tool", "first"));
            assertEquals(PermissionDecision.ASK,
                new PolicyPermissionGate(PermissionMode.ASK).check(call, tool).decision());
            assertEquals(PermissionDecision.DENY,
                new PolicyPermissionGate(PermissionMode.READ_ONLY).check(call, tool).decision());
            assertEquals(PermissionDecision.ALLOW,
                new PolicyPermissionGate(PermissionMode.AUTO).check(call, tool).decision());
        }
    }

    @Test
    void userPolicyCanTrustHintsAndOverrideIndividualTools() throws Exception {
        Path user = root.resolve("user.json");
        write(user, Map.of("trustReadOnlyHints", true, "readOnlyTools", List.of("write")));
        write(root.resolve(".mcp.json"), Map.of());
        try (McpManager manager = new McpManager(mapper, user, null, null)) {
            manager.tools(root, "fake");
            var tool = new McpCallTool(manager, root);
            var gate = new PolicyPermissionGate(PermissionMode.READ_ONLY);
            for (String name : List.of("first", "write")) {
                var args = Map.<String, Object>of("server", "fake", "tool", name);
                assertEquals(PermissionDecision.ALLOW,
                    gate.check(new ToolCall(name, "mcp_call", args), tool).decision());
                assertEquals("ok", tool.execute(args));
            }
            assertFalse(tool.readOnly(Map.of("server", "fake", "tool", "unknown")));
            assertTrue(new McpToolsTool(manager, root).execute(Map.of("server", "fake")).contains("[本地策略只读]"));
        }
    }

    @Test
    void explicitApprovalOverrideWinsEvenInAutoMode() throws Exception {
        Path user = root.resolve("user.json");
        write(user, Map.of("trustReadOnlyHints", true, "readOnlyTools", List.of("first"), "requireApprovalTools",
            List.of("first")));
        try (McpManager manager = new McpManager(mapper, user, null, null)) {
            manager.tools(root, "fake");
            var tool = new McpCallTool(manager, root);
            var call = new ToolCall("one", "mcp_call", Map.of("server", "fake", "tool", "first"));
            assertEquals(PermissionDecision.ASK,
                new PolicyPermissionGate(PermissionMode.AUTO).check(call, tool).decision());
            assertEquals(PermissionDecision.DENY,
                new PolicyPermissionGate(PermissionMode.READ_ONLY).check(call, tool).decision());
            assertFalse(tool.readOnly(call.args()));
        }
    }

    @Test
    void trustDoesNotFollowTheSameServerNameToDifferentWorkspaceCommand() throws Exception {
        Path user = root.resolve("user.json");
        write(user, Map.of("trustReadOnlyHints", true));
        var changed = new HashMap<>(config(Map.of("trustReadOnlyHints", true)));
        changed.put("env", Map.of("MCP_VALUE", "different"));
        Files.writeString(root.resolve(".mcp.json"),
            mapper.writeValueAsString(Map.of("mcpServers", Map.of("fake", changed))));
        try (McpManager manager = new McpManager(mapper, user, null, null)) {
            manager.tools(root, "fake");
            assertFalse(new McpCallTool(manager, root).readOnly(Map.of("server", "fake", "tool", "first")));
        }
    }

    @Test
    void revocationBetweenPermissionCheckAndExecutionPreventsTheCall() throws Exception {
        Path user = root.resolve("user.json");
        write(user, Map.of("trustReadOnlyHints", true));
        try (McpManager manager = new McpManager(mapper, user, null, null)) {
            manager.tools(root, "fake");
            var tool = new McpCallTool(manager, root);
            var args = Map.<String, Object>of("server", "fake", "tool", "first");
            assertTrue(tool.readOnly(args));
            write(user, Map.of());
            assertTrue(tool.execute(args).contains("本地只读策略、连接或工具列表已变化"));
            assertFalse(Files.exists(root.resolve("ready")), "拒绝后不得写入 tools/call");
            assertFalse(tool.readOnly(args));
        }
    }

    @Test
    void invalidUserPolicyCannotRetainAnEarlierGrant() throws Exception {
        Path user = root.resolve("user.json");
        write(user, Map.of("trustReadOnlyHints", true));
        write(root.resolve(".mcp.json"), Map.of());
        try (McpManager manager = new McpManager(mapper, user, null, null)) {
            manager.tools(root, "fake");
            var tool = new McpCallTool(manager, root);
            var args = Map.<String, Object>of("server", "fake", "tool", "first");
            assertTrue(tool.readOnly(args));
            Files.writeString(user, "invalid");
            assertTrue(tool.execute(args).startsWith("ERROR:"));
            assertFalse(tool.readOnly(args));
            assertFalse(Files.exists(root.resolve("ready")));
        }
    }

    @Test
    void actualExecutorRechecksTheGrantAfterItsPreExecutionGuard() throws Exception {
        Path user = root.resolve("user.json");
        write(user, Map.of("trustReadOnlyHints", true));
        try (McpManager manager = new McpManager(mapper, user, null, null)) {
            manager.tools(root, "fake");
            var registry = new ToolRegistry();
            registry.register(new McpCallTool(manager, root));
            var executor = new ToolExecutor(registry, new PolicyPermissionGate(PermissionMode.READ_ONLY),
                (id, name, summary) -> java.util.concurrent.CompletableFuture.completedFuture(false));
            var result = executor.execute(new ToolCall("check", "mcp_call", Map.of("server", "fake", "tool", "first")), event -> {}, () -> {
                try {
                    write(user, Map.of());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            assertFalse(result.usableEvidence());
            assertTrue(result.result().contains("本地只读策略、连接或工具列表已变化"));
            assertFalse(Files.exists(root.resolve("ready")));
        }
    }

}
