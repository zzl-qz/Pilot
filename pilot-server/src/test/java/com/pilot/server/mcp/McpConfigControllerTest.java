package com.pilot.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpTrustStore;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;

import tools.jackson.databind.ObjectMapper;

/** 配置管理接口：双格式 args 落盘、来源合并、工作区保护。不需要 Node。 */
class McpConfigControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;
    @TempDir
    Path dataRoot;

    private SessionService sessions;
    private McpManager manager;
    private McpConfigController controller;
    private String sessionId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        Files.writeString(workspace.resolve(".mcp.json"), mapper.writeValueAsString(Map.of("mcpServers",
            Map.of("from-workspace", Map.of("command", "node", "args", List.of("w.mjs"))))));
        sessions = new SessionService();
        sessionId = sessions.createSession("MCP 配置", workspace.toString()).id();
        McpTrustStore store = new McpTrustStore(mapper, dataRoot.resolve("mcp-trust.json"));
        manager = new McpManager(mapper, dataRoot.resolve("mcp.json"), null, () -> "", store);
        ObjectProvider<McpManager> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(manager);
        controller = new McpConfigController(sessions,
            new AgentProperties(workspace.toString(), com.pilot.core.permission.PermissionMode.AUTO, 8, 3, 5, 300, 1,
                0, 60000, false),
            mapper, provider);
    }

    @Test
    void mergedViewMarksSourceAndTrustState() throws IOException {
        Files.writeString(dataRoot.resolve("mcp.json"), mapper.writeValueAsString(Map.of("mcpServers",
            Map.of("from-user", Map.of("command", "node", "args", List.of("u.mjs"))))));
        List<McpConfigController.ServerPayload> view = controller.servers(sessionId);

        assertEquals(2, view.size());
        McpConfigController.ServerPayload user = view.stream()
            .filter(item -> item.name().equals("from-user")).findFirst().orElseThrow();
        McpConfigController.ServerPayload ws = view.stream()
            .filter(item -> item.name().equals("from-workspace")).findFirst().orElseThrow();
        assertEquals("user", user.source());
        assertTrue(user.usable());
        assertEquals("UNTRUSTED", user.trustState());
        assertEquals("workspace", ws.source());
    }

    @Test
    void addWritesUserLevelWithPlainArgsAndEnv() throws IOException {
        McpConfigController.ServerPayload created = controller.add(sessionId, new McpConfigController.AddRequest(
            "filesystem", "npx", "-y @scope/server-filesystem \"D:\\授权 目录\"", Map.of("KEY", "v", "EMPTY", "")));

        assertEquals("filesystem", created.name());
        assertEquals("user", created.source());
        assertEquals("npx -y @scope/server-filesystem D:\\授权 目录", created.command());

        // 落盘校验：args 已解析成数组、空白 env 未写入
        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        var entry = root.path("mcpServers").path("filesystem");
        assertEquals("npx", entry.path("command").asString());
        assertEquals(3, entry.path("args").size());
        assertEquals("@scope/server-filesystem", entry.path("args").get(1).asString());
        assertEquals("D:\\授权 目录", entry.path("args").get(2).asString());
        assertEquals(Map.of("KEY", "v"), mapper.convertValue(entry.path("env"), Map.class));
    }

    @Test
    void addAcceptsJsonArrayArgsEquivalently() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("json-style", "npx",
            "[\"-y\", \"@scope/server-filesystem\", \"D:\\\\授权 目录\"]", null));

        var entry = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")))
            .path("mcpServers").path("json-style");
        assertEquals(List.of("-y", "@scope/server-filesystem", "D:\\授权 目录"),
            List.of(mapper.convertValue(entry.path("args"), String[].class)));
    }

    @Test
    void addRejectsBadNamesCommandsAndArgs() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.add(sessionId, new McpConfigController.AddRequest("不合法 名字", "npx", null, null)))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.add(sessionId, new McpConfigController.AddRequest("ok", "", null, null)))
            .getStatusCode());
        ResponseStatusException args = assertThrows(ResponseStatusException.class,
            () -> controller.add(sessionId, new McpConfigController.AddRequest("ok", "npx", "\"unclosed", null)));
        assertEquals(HttpStatus.BAD_REQUEST, args.getStatusCode());
        assertTrue(args.getReason().contains("引号没有成对"), args.getReason());
    }

    @Test
    void addRefusesNameShadowedFromWorkspace() {
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
            () -> controller.add(sessionId, new McpConfigController.AddRequest("from-workspace", "npx", null, null)));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getReason().contains("工作区 .mcp.json"));
    }

    @Test
    void deleteOnlyRemovesUserLevelEntries() throws IOException {
        Files.writeString(dataRoot.resolve("mcp.json"), mapper.writeValueAsString(Map.of("mcpServers",
            Map.of("mine", Map.of("command", "node")))));

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
            () -> controller.delete(sessionId, "from-workspace"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getReason().contains("工作区 .mcp.json"), conflict.getReason());

        controller.delete(sessionId, "mine");
        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        assertTrue(root.path("mcpServers").path("mine").isMissingNode());
        // 用户级删干净后合并视图里也没有了；不存在的名字按 404 报
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> controller.delete(sessionId, "already-gone")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> controller.delete(sessionId, "mine")).getStatusCode());
    }

    @Test
    void overwritingUserEntryKeepsOthersIntact() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("first", "node", null, null));
        controller.add(sessionId, new McpConfigController.AddRequest("second", "node", "-a", null));
        controller.add(sessionId, new McpConfigController.AddRequest("first", "node", "-b", null));

        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        assertEquals(2, root.path("mcpServers").size());
        assertEquals(1, root.path("mcpServers").path("first").path("args").size());
        assertEquals("-b", root.path("mcpServers").path("first").path("args").get(0).asString());
    }

    @Test
    void viewReflectsTrustStateAfterRevocation() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("mine", "node", null, null));
        new McpTrustController(sessions,
            new AgentProperties(workspace.toString(), com.pilot.core.permission.PermissionMode.AUTO, 8, 3, 5, 300, 1,
                0, 60000, false),
            providerOf(manager)).resolve(sessionId, new McpTrustController.TrustRequest("trust", "mine", null));

        List<McpConfigController.ServerPayload> view = controller.servers(sessionId);
        McpConfigController.ServerPayload mine = view.stream()
            .filter(item -> item.name().equals("mine")).findFirst().orElseThrow();
        assertEquals("TRUSTED_UNPINNED", mine.trustState());

        // 配置变化后 → STALE
        controller.add(sessionId, new McpConfigController.AddRequest("mine", "node", "--changed", null));
        McpConfigController.ServerPayload stale = controller.servers(sessionId).stream()
            .filter(item -> item.name().equals("mine")).findFirst().orElseThrow();
        assertEquals("STALE", stale.trustState());
        assertNotNull(stale.trustNote() == null ? "" : stale.trustNote());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpManager> providerOf(McpManager manager) {
        ObjectProvider<McpManager> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(manager);
        return provider;
    }
}
