package com.pilot.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpTrustStore;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;

import tools.jackson.databind.ObjectMapper;

/** 信任管理接口：哈希由后端计算、信任落库、未运行服务器不能钉工具。不需要 Node。 */
class McpTrustControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;
    @TempDir
    Path dataDir;

    private SessionService sessions;
    private McpManager manager;
    private McpTrustController controller;
    private String sessionId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        Files.writeString(workspace.resolve(".mcp.json"),
            mapper.writeValueAsString(
                Map.of("mcpServers", Map.of("fake", Map.of("command", "node", "args", List.of("s.mjs")), "remote",
                    Map.of("url", "https://example.com/mcp"), "broken", Map.of("url", "ftp://example.com")))));
        sessions = new SessionService();
        sessionId = sessions.createSession("MCP 信任", workspace.toString()).id();
        McpTrustStore store = new McpTrustStore(mapper, dataDir.resolve("mcp-trust.json"));
        manager = new McpManager(mapper, null, null, () -> "", store);
        ObjectProvider<McpManager> provider = Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(manager);
        controller = new McpTrustController(sessions,
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 1, 0, 60000, false), provider);
    }

    private McpTrustController.TrustRequest trustRequest(String action, String server, String tool) {
        var config = manager.servers(workspace).get(server);
        String hash = config == null ? "" : McpTrustStore.hash(config);
        return new McpTrustController.TrustRequest(action, server, tool, hash, "sha256:tool");
    }

    @Test
    void trustViewReflectsConfiguredServers() {
        List<McpTrustController.TrustedServer> view = controller.trust(sessionId);
        assertEquals(3, view.size());
        assertEquals("broken", view.get(0).server());
        assertEquals("fake", view.get(1).server());
        assertEquals("UNTRUSTED", view.get(1).state());
        assertEquals("remote", view.get(2).server());
    }

    @Test
    void trustComputesHashOnServerSideAndPersists() {
        McpTrustController.TrustResponse response = controller.resolve(sessionId, trustRequest("trust", "fake", null));
        // 没启动进程，钉住留待下次启动时观测；配置哈希已由后端计算入库
        assertEquals("TRUSTED_UNPINNED", response.state());

        var record = manager.trustStore().record(workspace, "fake").orElseThrow();
        assertEquals(McpTrustStore.hash(manager.servers(workspace).get("fake")), record.configHash());
        assertEquals("TRUSTED_UNPINNED", manager.trustState(workspace, "fake"));
    }

    @Test
    void allowToolRequiresRunningServerAndTrustRecord() {
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest("allow-tool", "fake", "echo")));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 信任后仍未运行——一样不能钉
        controller.resolve(sessionId, trustRequest("trust", "fake", null));
        ResponseStatusException stillNotRunning = assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest("allow-tool", "fake", "echo")));
        assertEquals(HttpStatus.CONFLICT, stillNotRunning.getStatusCode());
        assertTrue(stillNotRunning.getReason().contains("未在运行"));

        ResponseStatusException unknownTool = assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest("allow-tool", "ghost", "x")));
        assertEquals(HttpStatus.NOT_FOUND, unknownTool.getStatusCode());
    }

    @Test
    void revokeRemovesRecordAndInvalidInputsAreRejected() {
        controller.resolve(sessionId, trustRequest("trust", "fake", null));
        assertEquals("UNTRUSTED", controller.resolve(sessionId, trustRequest("revoke", "fake", null)).state());
        assertTrue(manager.trustStore().record(workspace, "fake").isEmpty());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest(null, "fake", null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest("trust", "", null))).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest("trust", "ghost", null))).getStatusCode());
        assertEquals("TRUSTED_UNPINNED", controller.resolve(sessionId, trustRequest("trust", "remote", null)).state());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest("trust", "broken", null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, trustRequest("unknown-action", "fake", null))).getStatusCode());
    }

    @Test
    void trustRejectsStaleOrMissingApprovalHash() {
        String current = McpTrustStore.hash(manager.servers(workspace).get("fake"));
        ResponseStatusException mismatch = assertThrows(ResponseStatusException.class, () -> controller
            .resolve(sessionId, new McpTrustController.TrustRequest("trust", "fake", null, "sha256:old", null)));
        assertEquals(HttpStatus.CONFLICT, mismatch.getStatusCode());
        assertTrue(mismatch.getReason().contains("已变化"), mismatch.getReason());
        assertTrue(manager.trustStore().record(workspace, "fake").isEmpty());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.resolve(sessionId, new McpTrustController.TrustRequest("trust", "fake", null, null, null)))
            .getStatusCode());

        // 已有不匹配的旧记录时，allow-tool 不能把工具钉到旧配置上
        manager.trustStore().trust(workspace, "fake", "sha256:old");
        ResponseStatusException stale = assertThrows(ResponseStatusException.class, () -> controller.resolve(sessionId,
            new McpTrustController.TrustRequest("allow-tool", "fake", "echo", current, "sha256:tool")));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        assertTrue(stale.getReason().contains("不一致"), stale.getReason());
    }

    @Test
    void unknownSessionIsFortyFour() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> controller.resolve("missing", trustRequest("trust", "fake", null))).getStatusCode());
    }
}
