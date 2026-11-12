package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class McpConfigurationTest {
    @TempDir
    Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> config(String value) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return Map.of(
            "command", java.toString(), "args", List.of("-cp", System.getProperty("java.class.path"),
                McpProcessFixture.class.getName(), "echo", workspace.resolve("ready").toString()),
            "env", Map.of("MCP_VALUE", value));
    }

    private void save(String value) throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"),
            mapper.writeValueAsString(Map.of("mcpServers", Map.of("fake", config(value)))));
    }

    @Test
    void changedEnvironmentCannotReuseTheOldProcessAndRestartLoadsNewValue() throws Exception {
        save("one");
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            manager.tools(workspace, "fake");
            assertEquals("one",
                manager.call(workspace, "fake", "first", Map.of()).path("content").get(0).path("text").asString());
            save("two");
            McpException error =
                assertThrows(McpException.class, () -> manager.call(workspace, "fake", "first", Map.of()));
            assertTrue(error.getMessage().contains("重启"), error.getMessage());
            assertFalse(manager.running(workspace, "fake"));
            assertTrue(manager.knownTools(workspace, "fake").isEmpty());
            assertTrue(new McpToolsTool(manager, workspace).execute(Map.of()).contains("配置已变更"));
        }
        try (McpManager restarted = new McpManager(mapper, null, null, null)) {
            restarted.tools(workspace, "fake");
            assertEquals("two",
                restarted.call(workspace, "fake", "first", Map.of()).path("content").get(0).path("text").asString());
        }
    }

    @Test
    void callNeverSilentlyRestartsADeadConnection() throws Exception {
        save("one");
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            manager.tools(workspace, "fake");
            manager.client(workspace, "fake").close();
            assertThrows(McpException.class, () -> manager.call(workspace, "fake", "first", Map.of()));
            assertFalse(manager.running(workspace, "fake"));
            manager.tools(workspace, "fake");
            assertTrue(manager.running(workspace, "fake"));
        }
    }

    @Test
    void commandArgumentsAndRuntimePathChangesAreDetected() throws Exception {
        for (String field : List.of("command", "args", "runtimePath")) {
            save("one");
            var runtime = new AtomicReference<>("");
            try (McpManager manager = new McpManager(mapper, null, null, runtime::get)) {
                manager.tools(workspace, "fake");
                var next = new HashMap<>(config("one"));
                if (field.equals("command"))
                    next.put("command", "a-different-command");
                if (field.equals("args"))
                    next.put("args", List.of("--version"));
                if (field.equals("runtimePath"))
                    runtime.set(workspace.toString());
                Files.writeString(workspace.resolve(".mcp.json"),
                    mapper.writeValueAsString(Map.of("mcpServers", Map.of("fake", next))));
                assertTrue(manager.configurationChanged(workspace, "fake"), field);
                assertThrows(McpException.class, () -> manager.tools(workspace, "fake"));
            }
        }
    }

    @Test
    void deadProcessBetweenReadOnlyCheckAndDiscoveryCannotBeRestarted() throws Exception {
        save("one");
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            manager.tools(workspace, "fake");
            McpToolsTool listing = new McpToolsTool(manager, workspace);
            Map<String, Object> args = Map.of("server", "fake");
            assertTrue(listing.readOnly(args));
            manager.client(workspace, "fake").close();
            assertTrue(listing.execute(args).startsWith("ERROR: MCP 连接状态已变化"));
            assertFalse(manager.running(workspace, "fake"));
        }
    }

    @Test
    void configChangedWhileAwaitingApprovalCannotReplaceTheApprovedCommand() throws Exception {
        save("one");
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            McpToolsTool listing = new McpToolsTool(manager, workspace);
            Map<String, Object> args = Map.of("server", "fake");
            assertFalse(listing.readOnly(args));
            listing.approvalDescription(args);
            save("two");
            listing.readOnly(args); // 执行器批准后重新检查权限，不得覆盖已展示的配置版本。
            assertTrue(listing.execute(args).contains("配置在权限检查后已变化"));
            assertFalse(manager.running(workspace, "fake"));
        }
    }

    @Test
    void disabledWorkspaceServerOverridesUserConfigAndBrokenConfigFailsClosed() throws Exception {
        save("one");
        Path user = workspace.resolve("user.json");
        Files.copy(workspace.resolve(".mcp.json"), user);
        try (McpManager manager = new McpManager(mapper, user, null, null)) {
            manager.tools(workspace, "fake");
            Files.writeString(workspace.resolve(".mcp.json"), "{ broken");
            assertTrue(manager.knownTools(workspace, "fake").isEmpty());
            assertThrows(McpException.class, () -> manager.call(workspace, "fake", "first", Map.of()));
            Files.writeString(workspace.resolve(".mcp.json"), "{\"mcpServers\":{\"fake\":{\"disabled\":true}}}");
            assertFalse(manager.servers(workspace).get("fake").usable());
            assertThrows(McpException.class, () -> manager.tools(workspace, "fake"));
        }
    }

    @Test
    void disabledConfigurationImmediatelyRemovesKnownReadOnlyCapabilities() throws Exception {
        save("one");
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            manager.tools(workspace, "fake");
            Files.writeString(workspace.resolve(".mcp.json"), "{\"mcpServers\":{\"fake\":{\"disabled\":true}}}");
            assertTrue(manager.knownTools(workspace, "fake").isEmpty());
            assertFalse(manager.running(workspace, "fake"));
            assertThrows(McpException.class, () -> manager.call(workspace, "fake", "first", Map.of()));
        }
    }
}
