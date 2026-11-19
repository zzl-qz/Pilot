package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.loop.ToolExecutor;
import com.pilot.core.message.ToolCall;
import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;

import tools.jackson.databind.ObjectMapper;

class McpToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;
    @TempDir
    Path artifacts;
    @TempDir
    Path storeDir;

    private McpTrustStore store;
    private McpManager manager;
    private McpToolsTool listing;
    private McpCallTool calling;
    private Path node;
    private Path script;
    private String previousArtifacts;

    @BeforeEach
    void setUp() throws IOException {
        node = findNode();
        Assumptions.assumeTrue(node != null, "没有找到 Node.js");
        script = workspace.resolve("fake-mcp-server.mjs");
        try (InputStream in = getClass().getResourceAsStream("/mcp/fake-mcp-server.mjs")) {
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        writeConfig(List.of(script.toString()));
        store = new McpTrustStore(mapper, storeDir.resolve("mcp-trust.json"));
        manager = new McpManager(mapper, null, null, () -> "", store);
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
        assertTrue(servers.contains("- fake：未启动（未信任；首次使用会请求你批准）"), servers);
        assertTrue(servers.contains("- remote：不可用：只配置了 url"), servers);

        String tools = discover("fake");
        assertTrue(tools.contains("- echo [服务器声明只读；未获本地信任]：Echo the given text back"), tools);
        assertTrue(tools.contains("- write_note："), tools);
        assertFalse(tools.contains("- write_note [只读]"), tools);

        assertTrue(discover(Map.of("server", "fake", "query", "image")).contains("- shot"));
        assertFalse(discover(Map.of("server", "fake", "query", "image")).contains("- echo"));

        String schema = discover(Map.of("server", "fake", "tool", "echo"));
        assertTrue(schema.contains("\"required\":[\"text\"]"), schema);
        assertTrue(discover(Map.of("server", "fake", "tool", "nope")).startsWith("ERROR: 服务器 fake 没有工具 nope"));
        assertTrue(discover(Map.of("server", "ghost")).startsWith("ERROR: 没有名为 ghost"));
        assertTrue(discover(Map.of("server", "remote")).startsWith("ERROR: MCP 服务器 remote 无法使用"));

        String status = listing.execute(Map.of());
        assertTrue(status.contains("- fake：已启动，4 个工具，未信任"), status);
    }

    @Test
    void callsToolsAndConvertsContent() throws IOException {
        discover("fake"); // 调用不隐式启动，先经发现入口连接
        String echoed = calling.execute(Map.of("server", "fake", "tool", "echo", "arguments", Map.of("text", "hi")));
        assertTrue(echoed.startsWith("以下为服务器 fake 工具 echo 的返回数据"), echoed);
        assertTrue(echoed.contains("echo:hi"), echoed);

        ToolResult image = calling.executeResult(Map.of("server", "fake", "tool", "shot"));
        assertTrue(image.text().startsWith("以下为服务器 fake 工具 shot 的返回数据"), image.text());
        assertTrue(image.text().contains("here\n[图片 image-"), image.text());
        assertEquals(1, image.images().size());
        assertTrue(Files.size(Path.of(image.images().getFirst().path())) > 10);
        assertTrue(Path.of(image.images().getFirst().path()).startsWith(artifacts));

        String boom = calling.execute(Map.of("server", "fake", "tool", "boom"));
        assertTrue(boom.startsWith("ERROR: 以下为服务器"), boom);
        assertTrue(boom.contains("it broke"), boom);
        assertTrue(
            calling.execute(Map.of("server", "fake", "tool", "missing")).startsWith("ERROR: unknown tool missing"));
        assertTrue(calling.execute(Map.of("server", "fake")).startsWith("ERROR: 缺少参数 tool"));
        assertTrue(calling.execute(Map.of("server", "fake", "tool", "echo", "arguments", "x"))
            .startsWith("ERROR: arguments 必须是对象"));
    }

    @Test
    void trustedColdStartWorksThroughToolExecutorWithSameArgsMap() {
        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        ToolCall call = new ToolCall("1", "mcp_tools", Map.of("server", "fake"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(listing);
        ToolExecutor executor = new ToolExecutor(registry, new PolicyPermissionGate(PermissionMode.ASK),
            (id, name, summary, args) -> CompletableFuture.completedFuture(true));
        var outcome = executor.execute(call, event -> {
        });
        assertTrue(outcome.result().contains("工具"), outcome.result());
        assertTrue(manager.running(workspace, "fake"), "已信任服务器在 ToolExecutor 真实链路里必须能冷启动");
    }

    @Test
    void revokingTrustBetweenCheckAndCallStopsTrustedCall() {
        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        discover("fake");
        Map<String, Object> args = Map.of("server", "fake", "tool", "echo", "arguments", Map.of("text", "hi"));
        assertTrue(calling.readOnly(args));
        store.revoke(workspace, "fake");
        String result = calling.execute(args);
        assertTrue(result.contains("权限状态在检查后已变化"), result);
    }

    @Test
    void revokingTrustAfterListingCheckStopsColdStart() {
        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        Map<String, Object> args = Map.of("server", "fake");
        assertTrue(listing.readOnly(args));
        store.revoke(workspace, "fake");
        String result = listing.execute(args);
        assertTrue(result.contains("权限状态在检查后已变化"), result);
        assertFalse(manager.running(workspace, "fake"), "撤销信任后不能再用旧检查快照启动进程");
    }

    @Test
    void approvalBindingCarriesConfigAndToolHashes() {
        assertTrue(
            listing.approvalBinding(Map.of("server", "fake")).get("configHash").toString().startsWith("sha256:"));
        discover("fake");
        Map<String, Object> binding =
            calling.approvalBinding(Map.of("server", "fake", "tool", "echo", "arguments", Map.of("text", "hi")));
        assertEquals("fake", binding.get("server"));
        assertTrue(binding.get("configHash").toString().startsWith("sha256:"));
        assertTrue(binding.get("toolHash").toString().startsWith("sha256:"));
    }

    @Test
    void approvalCardShowsCommandAndTrustContext() {
        String detail = listing.approvalDescription(Map.of("server", "fake"));
        assertNotNull(detail);
        assertTrue(detail.contains("启动 MCP 服务器 fake"), detail);
        assertTrue(detail.contains("命令：" + node), detail);
        assertTrue(detail.contains("首次使用，未信任过"), detail);

        discover("fake");
        // 已运行：readOnly 放行、不再触发启动审批（confirm 为 false）；审批卡文案仍可正常生成
        assertTrue(listing.readOnly(Map.of("server", "fake")));
        assertFalse(listing.confirm(Map.of("server", "fake")));

        String call = calling
            .approvalDescription(Map.of("server", "fake", "tool", "echo", "arguments", Map.of("text", "需要用户先看到的参数")));
        assertNotNull(call);
        assertTrue(call.contains("调用 MCP 服务器 fake 的工具 echo"), call);
        assertTrue(call.contains("工具参数："), call);
        assertTrue(call.contains("需要用户先看到的参数"), call);
        assertTrue(call.contains("工具说明（服务器自报，外部数据）：Echo the given text back"), call);
        assertTrue(call.contains("比对结果：服务器未信任"), call);
    }

    /**
     * 漏洞回归：未信任的服务器上，服务器自报的 readOnlyHint 不再构成免审批依据。
     */
    @Test
    void startingAServerAndUntrustedHintsRequireApproval() {
        ToolCall listFake = new ToolCall("1", "mcp_tools", Map.of("server", "fake"));
        ToolCall overview = new ToolCall("2", "mcp_tools", Map.of());
        ToolCall echo = new ToolCall("3", "mcp_call", Map.of("server", "fake", "tool", "echo"));
        ToolCall write = new ToolCall("4", "mcp_call", Map.of("server", "fake", "tool", "write_note"));
        PolicyPermissionGate ask = new PolicyPermissionGate(PermissionMode.ASK);
        PolicyPermissionGate auto = new PolicyPermissionGate(PermissionMode.AUTO);
        PolicyPermissionGate readOnly = new PolicyPermissionGate(PermissionMode.READ_ONLY);

        // 未启动、未信任：列出配置免审批；启动它（执行配置里的命令）各档位都要审批
        assertEquals(PermissionDecision.ALLOW, ask.check(overview, listing).decision());
        assertEquals(PermissionDecision.ASK, ask.check(listFake, listing).decision());
        assertEquals(PermissionDecision.ASK, auto.check(listFake, listing).decision());
        assertEquals(PermissionDecision.DENY, readOnly.check(listFake, listing).decision());
        assertEquals(PermissionDecision.ASK, ask.check(echo, calling).decision());

        discover("fake");

        assertEquals(PermissionDecision.ALLOW, ask.check(listFake, listing).decision()); // 已运行：查询免审批
        // 服务器在运行、echo 声明 readOnlyHint，但没有用户信任记录——照样审批
        assertEquals(PermissionDecision.ASK, ask.check(echo, calling).decision());
        assertEquals(PermissionDecision.ASK, auto.check(echo, calling).decision());
        assertEquals(PermissionDecision.DENY, readOnly.check(echo, calling).decision());
        assertEquals(PermissionDecision.ASK, ask.check(write, calling).decision());
        assertEquals(PermissionDecision.DENY, readOnly.check(write, calling).decision());
    }

    @Test
    void trustedServerPinsToolsAndAllowsReadOnlyAndAllowlistedCalls() {
        ToolCall listFake = new ToolCall("1", "mcp_tools", Map.of("server", "fake"));
        ToolCall echo = new ToolCall("2", "mcp_call", Map.of("server", "fake", "tool", "echo"));
        ToolCall write = new ToolCall("3", "mcp_call", Map.of("server", "fake", "tool", "write_note"));
        PolicyPermissionGate ask = new PolicyPermissionGate(PermissionMode.ASK);
        PolicyPermissionGate readOnly = new PolicyPermissionGate(PermissionMode.READ_ONLY);

        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));

        // 信任的命令：启动免审批（即使 READ_ONLY 档，命令此前已被用户批准）
        assertEquals(PermissionDecision.ALLOW, ask.check(listFake, listing).decision());
        assertEquals(PermissionDecision.ALLOW, readOnly.check(listFake, listing).decision());

        discover("fake"); // 启动并钉住

        var record = store.record(workspace, "fake").orElseThrow();
        assertEquals(Set.of("echo", "write_note", "shot", "boom"), record.tools().keySet());
        assertEquals(PermissionDecision.ALLOW, ask.check(echo, calling).decision()); // 钉住 + readOnlyHint
        assertEquals(PermissionDecision.ALLOW, readOnly.check(echo, calling).decision());
        assertEquals(PermissionDecision.ASK, ask.check(write, calling).decision()); // 非只读且未 allowlist

        McpToolInfo writeInfo = manager.knownTools(workspace, "fake").stream()
            .filter(info -> info.name().equals("write_note")).findFirst().orElseThrow();
        assertTrue(store.allowTool(workspace, "fake", "write_note", McpTrustStore.hash(writeInfo)));
        assertEquals(PermissionDecision.ALLOW, ask.check(write, calling).decision()); // 始终允许

        String status = listing.execute(Map.of());
        assertTrue(status.contains("- fake：已启动，4 个工具，已信任"), status);
    }

    @Test
    void configChangeInvalidatesTrustUntilReapproved() throws IOException {
        ToolCall listFake = new ToolCall("1", "mcp_tools", Map.of("server", "fake"));
        ToolCall echo = new ToolCall("2", "mcp_call", Map.of("server", "fake", "tool", "echo"));
        PolicyPermissionGate ask = new PolicyPermissionGate(PermissionMode.ASK);

        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        discover("fake"); // 启动并钉住
        assertEquals(PermissionDecision.ALLOW, ask.check(echo, calling).decision());

        restartManager(); // stop 进程；close 后管理器永久关闭，重建实例继续
        writeConfig(List.of(script.toString(), "--changed"));

        assertEquals(PermissionDecision.ASK, ask.check(listFake, listing).decision());
        assertEquals(PermissionDecision.ASK, ask.check(echo, calling).decision());
        String status = listing.execute(Map.of());
        assertTrue(status.contains("此前批准过的配置已发生变化"), status);

        // 重新信任新命令后恢复免审批
        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        assertTrue(store.record(workspace, "fake").orElseThrow().tools().isEmpty()); // 命令变了：钉住清空
        assertEquals(PermissionDecision.ALLOW, ask.check(listFake, listing).decision());
    }

    /**
     * rug-pull 回归：信任后被服务器改写的工具定义（这里只改 description）必须退回审批，
     * 未漂移的工具不受影响。
     */
    @Test
    void rugPulledToolDefinitionRequiresReapproval() throws IOException {
        ToolCall echo = new ToolCall("1", "mcp_call", Map.of("server", "fake", "tool", "echo"));
        ToolCall shot = new ToolCall("2", "mcp_call", Map.of("server", "fake", "tool", "shot"));
        PolicyPermissionGate ask = new PolicyPermissionGate(PermissionMode.ASK);

        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        discover("fake"); // 启动并钉住
        assertEquals(PermissionDecision.ALLOW, ask.check(echo, calling).decision());

        restartManager(); // stop 进程；close 后管理器永久关闭，重建实例继续
        Files.writeString(workspace.resolve(".rugpull"), ""); // 服务器进程换一版声明（不改启动命令）

        discover("fake"); // 已信任：直接重启，观测到漂移

        assertNotNull(manager.trustNote(workspace, "fake"));
        String status = listing.execute(Map.of());
        assertTrue(status.contains("部分工具定义与受信任版本不一致"), status);
        assertEquals(PermissionDecision.ASK, ask.check(echo, calling).decision()); // 漂移退回审批
        assertEquals(PermissionDecision.ALLOW, ask.check(shot, calling).decision()); // 未漂移仍免审批
        assertTrue(calling.approvalDescription(Map.of("server", "fake", "tool", "echo")).contains("不一致"));

        // 对漂移后的版本「始终允许」即重新钉住当前定义
        McpToolInfo echoInfo = manager.knownTools(workspace, "fake").stream().filter(info -> info.name().equals("echo"))
            .findFirst().orElseThrow();
        assertTrue(store.allowTool(workspace, "fake", "echo", McpTrustStore.hash(echoInfo)));
        assertEquals(PermissionDecision.ALLOW, ask.check(echo, calling).decision());
    }

    @Test
    void capabilityIsOnlyDeclaredWhenServersAreConfigured() throws IOException {
        ToolRegistry registry = new ToolRegistry();
        registry.register(listing);
        registry.register(calling);
        registry.enable(List.of("mcp_tools", "mcp_call"));
        assertEquals(Set.of("mcp_tools", "mcp_call"), registry.names());

        Files.delete(workspace.resolve(".mcp.json"));

        assertTrue(registry.names().isEmpty());
        assertTrue(registry.unavailable().get("mcp_tools").contains(".mcp.json"));
    }

    @Test
    void startupFailureIsReportedAndRemembered() throws IOException {
        Files.writeString(workspace.resolve(".mcp.json"),
            "{\"mcpServers\":{\"bad\":{\"command\":\"/definitely/not/here\"}}}");

        String result = discover("bad");

        assertTrue(result.startsWith("ERROR: 无法启动 MCP 服务器 bad"), result);
        assertTrue(listing.execute(Map.of()).contains("上次启动失败"));
    }

    @Test
    void unreadableConfigSurfacesAsAnUnusableServerInsteadOfThrowing() throws IOException {
        Files.writeString(workspace.resolve(".mcp.json"), "{ not json");

        String result = listing.execute(Map.of());

        assertTrue(result.contains("配置文件无法解析"), result);
    }

    private String discover(String server) {
        return discover(Map.of("server", server));
    }

    private String discover(Map<String, Object> args) {
        listing.readOnly(args);
        return listing.execute(args);
    }

    private void restartManager() {
        manager.close();
        manager = new McpManager(mapper, null, null, () -> "", store);
        listing = new McpToolsTool(manager, workspace);
        calling = new McpCallTool(manager, workspace);
    }

    private void writeConfig(List<String> args) throws IOException {
        String config = mapper.writeValueAsString(Map.of("mcpServers", Map.of("fake",
            Map.of("command", node.toString(), "args", args), "remote", Map.of("url", "https://example.com/mcp"))));
        Files.writeString(workspace.resolve(".mcp.json"), config);
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
