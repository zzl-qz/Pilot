package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

/**
 * 决策矩阵 golden test（docs-public/design-mcp-trust.md 的行 1–4、9 与状态迁移）。
 * 不启动进程，只覆盖「配置 + 信任记录」可决定的行；依赖运行态的行 5–8 见 McpToolsTest。
 */
class McpTrustPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;
    @TempDir
    Path dir;

    @Test
    void listingAndCallDecisionsAcrossTrustStates() throws IOException {
        McpTrustStore store = new McpTrustStore(mapper, dir.resolve("trust.json"));
        writeConfig(List.of("a"));

        // S0（未信任）：启动要审批，调用一律审批
        McpManager manager = new McpManager(mapper, null, null, () -> "", store);
        assertFalse(McpTrustPolicy.listingReadOnly(manager, workspace, "fake"));
        assertTrue(McpTrustPolicy.listingNeedsConfirm(manager, workspace, "fake"));
        assertFalse(McpTrustPolicy.callReadOnly(manager, workspace, "fake", "echo"));
        assertTrue(McpTrustPolicy.callNeedsConfirm(manager, workspace, "fake", "echo"));

        // 行 9：报错路径放行且不确认
        assertTrue(McpTrustPolicy.listingReadOnly(manager, workspace, null));
        assertFalse(McpTrustPolicy.listingNeedsConfirm(manager, workspace, null));
        assertTrue(McpTrustPolicy.callReadOnly(manager, workspace, null, null));
        assertTrue(McpTrustPolicy.callReadOnly(manager, workspace, "ghost", "echo"));
        assertTrue(McpTrustPolicy.callReadOnly(manager, workspace, "fake", null));
        assertFalse(McpTrustPolicy.callNeedsConfirm(manager, workspace, "ghost", "echo"));

        // S1（已信任命令，未钉住工具）：启动免审批，调用仍审批
        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        assertTrue(McpTrustPolicy.listingReadOnly(manager, workspace, "fake"));
        assertFalse(McpTrustPolicy.listingNeedsConfirm(manager, workspace, "fake"));
        assertFalse(McpTrustPolicy.callReadOnly(manager, workspace, "fake", "echo"));
        assertTrue(McpTrustPolicy.callNeedsConfirm(manager, workspace, "fake", "echo"));

        // S2（钉住）：无进程时观测不到工具，调用仍审批——运行态放行在 McpToolsTest 验证
        store.pinTools(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")),
            Map.of("echo", new McpTrustStore.ToolPin("sha256:whatever", false)));
        assertFalse(McpTrustPolicy.callReadOnly(manager, workspace, "fake", "echo"));

        // S3（配置变化）：全部退回审批
        writeConfig(List.of("a", "changed"));
        assertFalse(McpTrustPolicy.listingReadOnly(manager, workspace, "fake"));
        assertTrue(McpTrustPolicy.listingNeedsConfirm(manager, workspace, "fake"));
        assertFalse(McpTrustPolicy.callReadOnly(manager, workspace, "fake", "echo"));
        assertFalse(McpTrustPolicy.trusted(manager, workspace, "fake"));

        // 配置不可用（url-only）：报错路径放行
        Files.writeString(workspace.resolve(".mcp.json"),
            mapper.writeValueAsString(Map.of("mcpServers", Map.of("remote", Map.of("url", "https://example.com")))));
        assertTrue(McpTrustPolicy.listingReadOnly(manager, workspace, "remote"));
        assertFalse(McpTrustPolicy.listingNeedsConfirm(manager, workspace, "remote"));
        assertTrue(McpTrustPolicy.callReadOnly(manager, workspace, "remote", "anything"));

        // 撤销后回到 S0
        writeConfig(List.of("a"));
        store.trust(workspace, "fake", McpTrustStore.hash(manager.servers(workspace).get("fake")));
        store.revoke(workspace, "fake");
        assertFalse(McpTrustPolicy.listingReadOnly(manager, workspace, "fake"));
        assertTrue(McpTrustPolicy.listingNeedsConfirm(manager, workspace, "fake"));
    }

    @Test
    void nullTrustStoreTreatsEverythingAsUntrusted() throws IOException {
        writeConfig(List.of("a"));
        McpManager manager = new McpManager(mapper, null, null, () -> "");

        assertFalse(McpTrustPolicy.listingReadOnly(manager, workspace, "fake"));
        assertTrue(McpTrustPolicy.listingNeedsConfirm(manager, workspace, "fake"));
        assertFalse(McpTrustPolicy.callReadOnly(manager, workspace, "fake", "echo"));
        assertFalse(McpTrustPolicy.trusted(manager, workspace, "fake"));
    }

    private void writeConfig(List<String> args) throws IOException {
        Files.writeString(workspace.resolve(".mcp.json"), mapper
            .writeValueAsString(Map.of("mcpServers", Map.of("fake", Map.of("command", "some-command", "args", args)))));
    }
}
