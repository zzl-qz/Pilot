package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;

import tools.jackson.databind.ObjectMapper;

class McpToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;
    @TempDir
    Path artifacts;

    private McpManager manager;
    private McpToolsTool listing;
    private McpCallTool calling;
    private String previousArtifacts;

    @BeforeEach
    void setUp() throws IOException {
        Path node = findNode();
        Assumptions.assumeTrue(node != null, "没有找到 Node.js");
        Path script = workspace.resolve("fake-mcp-server.mjs");
        try (InputStream in = getClass().getResourceAsStream("/mcp/fake-mcp-server.mjs")) {
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        String config = mapper.writeValueAsString(
            Map.of("mcpServers", Map.of("fake", Map.of("command", node.toString(), "args", List.of(script.toString())),
                "remote", Map.of("url", "https://example.com/mcp"))));
        Files.writeString(workspace.resolve(".mcp.json"), config);
        manager = new McpManager(mapper, null, null, () -> "");
        listing = new McpToolsTool(manager, workspace);
        calling = new McpCallTool(manager, workspace);
        previousArtifacts = System.getProperty("pilot.artifacts.dir");
        System.setProperty("pilot.artifacts.dir", artifacts.toString());
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        if (previousArtifacts == null) {
            System.clearProperty("pilot.artifacts.dir");
        } else {
            System.setProperty("pilot.artifacts.dir", previousArtifacts);
        }
    }

    @Test
    void discoversServersThenToolsThenSchemaOnDemand() {
        String servers = listing.execute(Map.of());
        assertTrue(servers.contains("- fake：未启动"), servers);
        assertTrue(servers.contains("- remote：不可用：只配置了 url"), servers);

        String tools = listing.execute(Map.of("server", "fake"));
        assertTrue(tools.contains("- echo [服务器声明只读；未获本地信任]：Echo the given text back"), tools);
        assertTrue(tools.contains("- write_note："), tools);
        assertFalse(tools.contains("- write_note [只读]"), tools);

        assertTrue(listing.execute(Map.of("server", "fake", "query", "image")).contains("- shot"));
        assertFalse(listing.execute(Map.of("server", "fake", "query", "image")).contains("- echo"));

        String schema = listing.execute(Map.of("server", "fake", "tool", "echo"));
        assertTrue(schema.contains("\"required\":[\"text\"]"), schema);
        assertTrue(listing.execute(Map.of("server", "fake", "tool", "nope")).startsWith("ERROR: 服务器 fake 没有工具 nope"));
        assertTrue(listing.execute(Map.of("server", "ghost")).startsWith("ERROR: 没有名为 ghost"));
        assertTrue(listing.execute(Map.of("server", "remote")).startsWith("ERROR: MCP 服务器 remote 无法使用"));

        assertTrue(listing.execute(Map.of()).contains("- fake：已启动，4 个工具"));
    }

    @Test
    void callsToolsAndConvertsContent() throws IOException {
        listing.execute(Map.of("server", "fake"));
        assertEquals("echo:hi",
            calling.execute(Map.of("server", "fake", "tool", "echo", "arguments", Map.of("text", "hi"))));

        ToolResult image = calling.executeResult(Map.of("server", "fake", "tool", "shot"));
        assertTrue(image.text().startsWith("here\n[图片 image-"), image.text());
        assertEquals(1, image.images().size());
        assertTrue(Files.size(Path.of(image.images().getFirst().path())) > 10);
        assertTrue(Path.of(image.images().getFirst().path()).startsWith(artifacts));

        assertEquals("ERROR: it broke", calling.execute(Map.of("server", "fake", "tool", "boom")));
        assertTrue(
            calling.execute(Map.of("server", "fake", "tool", "missing")).startsWith("ERROR: unknown tool missing"));
        assertTrue(calling.execute(Map.of("server", "fake")).startsWith("ERROR: 缺少参数 tool"));
        assertTrue(calling.execute(Map.of("server", "fake", "tool", "echo", "arguments", "x"))
            .startsWith("ERROR: arguments 必须是对象"));
    }

    @Test
    void startingAServerAndUntrustedHintsRequireApproval() {
        ToolCall listFake = new ToolCall("1", "mcp_tools", Map.of("server", "fake"));
        ToolCall overview = new ToolCall("2", "mcp_tools", Map.of());
        ToolCall echo = new ToolCall("3", "mcp_call", Map.of("server", "fake", "tool", "echo"));
        ToolCall write = new ToolCall("4", "mcp_call", Map.of("server", "fake", "tool", "write_note"));
        PolicyPermissionGate ask = new PolicyPermissionGate(PermissionMode.ASK);
        PolicyPermissionGate readOnly = new PolicyPermissionGate(PermissionMode.READ_ONLY);

        // 服务器还没起来：列出配置免审批，启动它（执行配置里的命令）要审批，调用未知工具默认要审批。
        assertEquals(PermissionDecision.ALLOW, ask.check(overview, listing).decision());
        assertEquals(PermissionDecision.ASK, ask.check(listFake, listing).decision());
        assertEquals(PermissionDecision.DENY, readOnly.check(listFake, listing).decision());
        assertEquals(PermissionDecision.ASK, ask.check(echo, calling).decision());

        listing.execute(Map.of("server", "fake"));

        assertEquals(PermissionDecision.ALLOW, ask.check(listFake, listing).decision());
        assertEquals(PermissionDecision.DENY, readOnly.check(echo, calling).decision());
        assertEquals(PermissionDecision.ASK, ask.check(write, calling).decision());
        assertEquals(PermissionDecision.DENY, readOnly.check(write, calling).decision());
    }

    @Test
    void capabilityIsOnlyDeclaredWhenServersAreConfigured() throws IOException {
        ToolRegistry registry = new ToolRegistry();
        registry.register(listing);
        registry.register(calling);
        assertEquals(Set.of("mcp_tools", "mcp_call"), registry.names());

        Files.delete(workspace.resolve(".mcp.json"));

        assertTrue(registry.names().isEmpty());
        assertTrue(registry.unavailable().get("mcp_tools").contains(".mcp.json"));
    }

    @Test
    void startupFailureIsReportedAndRemembered() throws IOException {
        Files.writeString(workspace.resolve(".mcp.json"),
            "{\"mcpServers\":{\"bad\":{\"command\":\"/definitely/not/here\"}}}");

        String result = listing.execute(Map.of("server", "bad"));

        assertTrue(result.startsWith("ERROR: 无法启动 MCP 服务器 bad"), result);
        assertTrue(listing.execute(Map.of()).contains("上次启动失败"));
    }

    @Test
    void unreadableConfigSurfacesAsAnUnusableServerInsteadOfThrowing() throws IOException {
        Files.writeString(workspace.resolve(".mcp.json"), "{ not json");

        String result = listing.execute(Map.of());

        assertTrue(result.contains("配置文件无法解析"), result);
    }

    private static Path findNode() {
        String path = System.getenv("PATH") == null ? "" : System.getenv("PATH");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : List.of("node", "node.exe")) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
