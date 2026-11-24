package com.pilot.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpServerConfig;
import com.pilot.core.mcp.McpTrustStore;
import com.pilot.core.permission.PermissionMode;
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
        Files.writeString(workspace.resolve(".mcp.json"), mapper.writeValueAsString(
            Map.of("mcpServers", Map.of("from-workspace", Map.of("command", "node", "args", List.of("w.mjs"))))));
        sessions = new SessionService();
        sessionId = sessions.createSession("MCP 配置", workspace.toString()).id();
        McpTrustStore store = new McpTrustStore(mapper, dataRoot.resolve("mcp-trust.json"));
        manager = new McpManager(mapper, dataRoot.resolve("mcp.json"), null, () -> "", store);
        ObjectProvider<McpManager> provider = Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(manager);
        controller = new McpConfigController(sessions,
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 1, 0, 60000, false), mapper,
            provider);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 探活会真的拉起 node 进程；不关掉会占着 @TempDir（Windows 上直接导致临时目录删不掉）
        manager.close();
    }

    @Test
    void mergedViewMarksSourceAndTrustState() throws IOException {
        Files.writeString(dataRoot.resolve("mcp.json"), mapper.writeValueAsString(
            Map.of("mcpServers", Map.of("from-user", Map.of("command", "node", "args", List.of("u.mjs"))))));
        List<McpConfigController.ServerPayload> view = controller.servers(sessionId);

        assertEquals(2, view.size());
        McpConfigController.ServerPayload user =
            view.stream().filter(item -> item.name().equals("from-user")).findFirst().orElseThrow();
        McpConfigController.ServerPayload ws =
            view.stream().filter(item -> item.name().equals("from-workspace")).findFirst().orElseThrow();
        assertEquals("user", user.source());
        assertTrue(user.usable());
        assertEquals("UNTRUSTED", user.trustState());
        assertEquals("workspace", ws.source());
    }

    @Test
    void addWritesUserLevelWithPlainArgsAndEnv() throws IOException {
        McpConfigController.ServerPayload created =
            controller.add(sessionId, new McpConfigController.AddRequest("filesystem", "npx",
                "-y @scope/server-filesystem \"D:\\授权 目录\"", Map.of("KEY", "v", "EMPTY", "")));

        assertEquals("filesystem", created.name());
        assertEquals("user", created.source());
        assertEquals("npx -y @scope/server-filesystem D:\\授权 目录", created.command());

        // 落盘校验：args 已解析成数组，显式给出的空 env 值也保留
        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        var entry = root.path("mcpServers").path("filesystem");
        assertEquals("npx", entry.path("command").asString());
        assertEquals(3, entry.path("args").size());
        assertEquals("@scope/server-filesystem", entry.path("args").get(1).asString());
        assertEquals("D:\\授权 目录", entry.path("args").get(2).asString());
        assertEquals(Map.of("KEY", "v", "EMPTY", ""), mapper.convertValue(entry.path("env"), Map.class));
    }

    @Test
    void addAcceptsJsonArrayArgsEquivalently() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("json-style", "npx",
            "[\"-y\", \"@scope/server-filesystem\", \"D:\\\\授权 目录\"]", null));

        var entry =
            mapper.readTree(Files.readString(dataRoot.resolve("mcp.json"))).path("mcpServers").path("json-style");
        assertEquals(List.of("-y", "@scope/server-filesystem", "D:\\授权 目录"),
            List.of(mapper.convertValue(entry.path("args"), String[].class)));
    }

    @Test
    void addRejectsBadNamesCommandsAndArgs() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> controller.add(sessionId, new McpConfigController.AddRequest("不合法 名字", "npx", null, null)))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
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
        Files.writeString(dataRoot.resolve("mcp.json"),
            mapper.writeValueAsString(Map.of("mcpServers", Map.of("mine", Map.of("command", "node")))));

        ResponseStatusException conflict =
            assertThrows(ResponseStatusException.class, () -> controller.delete(sessionId, "from-workspace"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getReason().contains("工作区 .mcp.json"), conflict.getReason());

        controller.delete(sessionId, "mine");
        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        assertTrue(root.path("mcpServers").path("mine").isMissingNode());
        // 用户级删干净后合并视图里也没有了；不存在的名字按 404 报
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> controller.delete(sessionId, "already-gone"))
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> controller.delete(sessionId, "mine")).getStatusCode());
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
    void importWritesBothFormatsAndPreservesArgsEnv() throws IOException {
        McpConfigController.ImportResponse wrapped = controller.importServers(sessionId,
            new McpConfigController.ImportRequest(
                "{\"mcpServers\": {\"one\": {\"command\": \"npx\", \"args\": [\"-y\", \"@scope/server\"], "
                    + "\"env\": {\"K\": \"v\"}}}}"));
        assertEquals(List.of("one"), wrapped.imported());
        assertEquals(List.of(), wrapped.overwritten());

        controller.importServers(sessionId,
            new McpConfigController.ImportRequest("{\"two\": {\"command\": \"node\"}}"));

        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        assertEquals(2, root.path("mcpServers").size());
        assertEquals("-y", root.path("mcpServers").path("one").path("args").get(0).asString());
        assertEquals("v", root.path("mcpServers").path("one").path("env").path("K").asString());
        assertEquals("node", root.path("mcpServers").path("two").path("command").asString());
    }

    @Test
    void importOverwritesUserLevelEntriesAndReportsThem() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("mine", "node", "-old", null));
        ResponseStatusException needsConfirm =
            assertThrows(ResponseStatusException.class, () -> controller.importServers(sessionId,
                new McpConfigController.ImportRequest("{\"mine\": {\"command\": \"node\", \"args\": [\"-new\"]}}")));
        assertEquals(HttpStatus.CONFLICT, needsConfirm.getStatusCode());
        assertTrue(needsConfirm.getReason().contains("mine"), needsConfirm.getReason());

        McpConfigController.ImportResponse response = controller.importServers(sessionId,
            new McpConfigController.ImportRequest("{\"mine\": {\"command\": \"node\", \"args\": [\"-new\"]}}", true));
        assertEquals(List.of("mine"), response.imported());
        assertEquals(List.of("mine"), response.overwritten());

        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        assertEquals("-new", root.path("mcpServers").path("mine").path("args").get(0).asString());
    }

    @Test
    void importIsAtomicWorkspaceConflictsRejectTheWholeBatch() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("before", "node", null, null));
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
            () -> controller.importServers(sessionId, new McpConfigController.ImportRequest(
                "{\"fresh\": {\"command\": \"node\"}, \"from-workspace\": {\"command\": \"node\"}}")));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getReason().contains("from-workspace"), conflict.getReason());
        assertTrue(conflict.getReason().contains("未写入任何内容"), conflict.getReason());

        // 批次整体未写入：fresh 不应存在，before 保持原样
        var root = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json")));
        assertTrue(root.path("mcpServers").path("fresh").isMissingNode());
        assertTrue(root.path("mcpServers").has("before"));
    }

    @Test
    void importRejectsBadEntriesWithSpecificReasons() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> controller.importServers(sessionId, new McpConfigController.ImportRequest("   ")))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> controller.importServers(sessionId, new McpConfigController.ImportRequest("{ not json")))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> controller.importServers(sessionId,
                new McpConfigController.ImportRequest("{\"不合法 名字\": {\"command\": \"npx\"}}"))).getStatusCode());
        ResponseStatusException badUrl = assertThrows(ResponseStatusException.class,
            () -> controller.importServers(sessionId, new McpConfigController.ImportRequest(
                "{\"remote\": {\"type\": \"http\", \"url\": \"ftp://example.com\"}}")));
        assertEquals(HttpStatus.BAD_REQUEST, badUrl.getStatusCode());
        assertTrue(badUrl.getReason().contains("http/https"), badUrl.getReason());
        ResponseStatusException disabled =
            assertThrows(ResponseStatusException.class, () -> controller.importServers(sessionId,
                new McpConfigController.ImportRequest("{\"off\": {\"command\": \"node\", \"disabled\": true}}")));
        assertEquals(HttpStatus.BAD_REQUEST, disabled.getStatusCode());
        assertTrue(disabled.getReason().contains("不允许导入"), disabled.getReason());
        ResponseStatusException noCommand = assertThrows(ResponseStatusException.class, () -> controller
            .importServers(sessionId, new McpConfigController.ImportRequest("{\"bare\": {\"args\": [\"-y\"]}}")));
        assertEquals(HttpStatus.BAD_REQUEST, noCommand.getStatusCode());
        assertTrue(noCommand.getReason().contains("缺少 command"), noCommand.getReason());
    }

    @Test
    void supportsAddingAndImportingRemoteHttpAndSseServersWithoutLeakingHeaders() throws IOException {
        McpConfigController.ServerPayload added = controller.add(sessionId, new McpConfigController.AddRequest("docs",
            null, null, null, "http", "https://mcp.example.com/v1", Map.of("Authorization", "Bearer top-secret")));
        assertTrue(added.usable());
        assertEquals("HTTP https://mcp.example.com/v1", added.command());
        assertFalse(added.toString().contains("top-secret"), "返回给界面的视图不得泄露认证头");

        McpConfigController.ImportResponse imported = controller.importServers(sessionId,
            new McpConfigController.ImportRequest(
                "{\"sse-remote\": {\"type\": \"sse\", \"url\": \"https://sse.example.com/events\", "
                    + "\"headers\": {\"X-Token\": \"abc\"}}}"));
        assertEquals(List.of("sse-remote"), imported.imported());
        McpServerConfig cfg = manager.servers(workspace).get("sse-remote");
        assertTrue(cfg.isRemote());
        assertEquals("sse", cfg.transport());
        assertEquals("https://sse.example.com/events", cfg.url());
    }

    @Test
    void probeReportsFailuresAsOkFalseWithReason() throws IOException {
        ResponseStatusException missing =
            assertThrows(ResponseStatusException.class, () -> controller.probe(sessionId, "unknown"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        // 启动失败的 command 与非法协议 url 条目都按 ok=false 返回原因（每个测试方法有独立的 @TempDir）
        writeUserConfig(
            Map.of("bad", Map.of("command", "/definitely/not/here"), "bad-remote", Map.of("url", "ftp://example.com")));
        McpConfigController.ProbeResponse broken = controller.probe(sessionId, "bad");
        assertFalse(broken.ok());
        assertTrue(broken.error().contains("无法启动"), broken.error());
        assertFalse(broken.running());
        assertTrue(broken.tools().isEmpty());

        McpConfigController.ProbeResponse badRemote = controller.probe(sessionId, "bad-remote");
        assertFalse(badRemote.ok());
        assertTrue(badRemote.error().contains("http/https"), badRemote.error());
    }

    @Test
    void probeStartsServerAndListsTools() throws IOException {
        Path node = findNode();
        Assumptions.assumeTrue(node != null, "没有找到 Node.js");
        writeUserConfig(
            Map.of("fake", Map.of("command", node.toString(), "args", List.of(fakeServerScript().toString()))));

        McpConfigController.ProbeResponse response = controller.probe(sessionId, "fake");

        assertTrue(response.ok(), response.error());
        assertTrue(response.running());
        assertEquals(2, response.tools().size());
        assertEquals("echo", response.tools().getFirst().name());
        assertTrue(response.tools().getFirst().readOnly());
        assertTrue(response.tools().stream().anyMatch(tool -> tool.name().equals("write") && !tool.readOnly()));
    }

    /** 写一个最小的 stdio JSON-RPC 服务器脚本（core 的测试资源对 server 模块不可见，内联自包含）。 */
    private Path fakeServerScript() throws IOException {
        Path script = dataRoot.resolve("fake-mcp-server.mjs");
        Files.writeString(script,
            """
                import { createInterface } from 'node:readline';
                const reply = (id, result) => console.log(JSON.stringify({ jsonrpc: '2.0', id, result }));
                createInterface({ input: process.stdin }).on('line', line => {
                  const m = JSON.parse(line);
                  if (m.method === 'initialize')
                    return reply(m.id, { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'fake', version: '1' } });
                  if (m.method === 'tools/list')
                    return reply(m.id, { tools: [
                      { name: 'echo', description: 'Echo back', annotations: { readOnlyHint: true }, inputSchema: { type: 'object' } },
                      { name: 'write', description: 'Write data', inputSchema: { type: 'object' } }
                    ]});
                  if (m.id !== undefined) reply(m.id, { content: [] });
                });
                """);
        return script;
    }

    /** 直接写用户级配置（绕过 AddRequest 的字段校验，用于构造 url-only 等特殊条目）。 */
    private void writeUserConfig(Map<String, Object> servers) throws IOException {
        Files.writeString(dataRoot.resolve("mcp.json"), mapper.writeValueAsString(Map.of("mcpServers", servers)));
    }

    private static Path findNode() {
        String path = System.getenv("PATH") == null ? "" : System.getenv("PATH");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : List.of("node", "node.exe")) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    @Test
    void viewReflectsTrustStateAfterRevocation() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("mine", "node", null, null));
        new McpTrustController(sessions,
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 1, 0, 60000, false),
            providerOf(manager))
            .resolve(sessionId, new McpTrustController.TrustRequest("trust", "mine", null,
                McpTrustStore.hash(manager.servers(workspace).get("mine")), null));

        List<McpConfigController.ServerPayload> view = controller.servers(sessionId);
        McpConfigController.ServerPayload mine =
            view.stream().filter(item -> item.name().equals("mine")).findFirst().orElseThrow();
        assertEquals("TRUSTED_UNPINNED", mine.trustState());

        // 配置变化后 → STALE
        controller.add(sessionId, new McpConfigController.AddRequest("mine", "node", "--changed", null));
        McpConfigController.ServerPayload stale =
            controller.servers(sessionId).stream().filter(item -> item.name().equals("mine")).findFirst().orElseThrow();
        assertEquals("STALE", stale.trustState());
        assertEquals(McpTrustStore.hash(manager.servers(workspace).get("mine")), stale.configHash());
    }

    @Test
    void corruptUserConfigIsNotSilentlyOverwritten() throws IOException {
        Path file = dataRoot.resolve("mcp.json");
        Files.writeString(file, "{ broken");
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
            () -> controller.add(sessionId, new McpConfigController.AddRequest("ok", "node", null, null)));
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals("{ broken", Files.readString(file), "损坏的配置应保持原样，等待用户修复");
    }

    @Test
    void oversizedImportIsRejectedBeforeWriting() throws IOException {
        String huge = "x".repeat(1024 * 1024 + 100);
        String config = mapper
            .writeValueAsString(Map.of("mcpServers", Map.of("big", Map.of("command", "node", "args", List.of(huge)))));
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
            () -> controller.importServers(sessionId, new McpConfigController.ImportRequest(config, true)));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertTrue(failure.getReason().contains("1 MiB"), failure.getReason());
        assertTrue(!Files.exists(dataRoot.resolve("mcp.json")), "超限时不应写出半截配置");
    }

    @Test
    void importRejectsLocalPermissionFields() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
            () -> controller.importServers(sessionId, new McpConfigController.ImportRequest(
                "{\"evil\": {\"command\": \"npx\", \"pilotPermissions\": {\"workspaces\": [\"/tmp\"]}}}")));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertTrue(failure.getReason().contains("pilotPermissions"), failure.getReason());
        // 整批不写入
        assertTrue(manager.servers(workspace).get("evil") == null);
    }

    @Test
    void deleteRevokesTrustAndKeepsWorkspaceDefinitionsReadOnly() throws IOException {
        controller.add(sessionId, new McpConfigController.AddRequest("mine", "node", null, null));
        manager.trustStore().trust(workspace, "mine", McpTrustStore.hash(manager.servers(workspace).get("mine")));
        controller.delete(sessionId, "mine");
        assertTrue(manager.trustStore().record(workspace, "mine").isEmpty(), "删除配置时也要撤销信任");
        assertTrue(manager.servers(workspace).get("mine") == null);

        // 工作区同名条目不可通过接口删除/覆盖
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class, () -> controller.delete(sessionId, "from-workspace"))
                .getStatusCode());
    }

    @Test
    void overwriteKeepsHandWrittenLocalPolicyFields() throws IOException {
        Files.writeString(dataRoot.resolve("mcp.json"),
            mapper.writeValueAsString(Map.of("mcpServers", Map.of("mine", Map.of("command", "node", "args",
                List.of("old.mjs"), "pilotPermissions", Map.of("readOnlyTools", List.of("old_tool")))))));
        controller.add(sessionId, new McpConfigController.AddRequest("mine", "node", "--new", null));
        var entry = mapper.readTree(Files.readString(dataRoot.resolve("mcp.json"))).path("mcpServers").path("mine");
        assertEquals("node", entry.path("command").asString());
        assertEquals("--new", entry.path("args").get(0).asString());
        assertTrue(entry.path("pilotPermissions").path("readOnlyTools").isArray(), "表单覆盖不能丢掉用户手写的本地授权字段");
    }

    @Test
    void workspaceDefinitionIsTheEffectiveSource() throws IOException {
        Files.writeString(dataRoot.resolve("mcp.json"), mapper.writeValueAsString(
            Map.of("mcpServers", Map.of("from-workspace", Map.of("command", "node", "args", List.of("user.mjs"))))));
        McpConfigController.ServerPayload shadowed = controller.servers(sessionId).stream()
            .filter(item -> item.name().equals("from-workspace")).findFirst().orElseThrow();
        assertEquals("workspace", shadowed.source(), "两边同名时生效来源是工作区");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> controller.add(sessionId, new McpConfigController.AddRequest("from-workspace", "node", null, null)))
            .getStatusCode());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpManager> providerOf(McpManager manager) {
        ObjectProvider<McpManager> provider = Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(manager);
        return provider;
    }
}
