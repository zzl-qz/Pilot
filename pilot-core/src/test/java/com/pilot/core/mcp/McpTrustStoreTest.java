package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

/** 信任库的哈希、状态迁移与落盘行为；不需要 Node。 */
class McpTrustStoreTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dir;
    @TempDir
    Path workspace;

    @Test
    void configHashIgnoresEnvOrderButTracksEveryField() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("B_KEY", "2");
        env.put("A_KEY", "1");
        McpServerConfig first = new McpServerConfig("fake", "node", List.of("a", "b"), env, null);
        Map<String, String> shuffled = new LinkedHashMap<>();
        shuffled.put("A_KEY", "1");
        shuffled.put("B_KEY", "2");
        McpServerConfig second = new McpServerConfig("fake", "node", List.of("a", "b"), shuffled, null);
        assertEquals(McpTrustStore.hash(first), McpTrustStore.hash(second));

        assertNotEquals(McpTrustStore.hash(first),
            McpTrustStore.hash(new McpServerConfig("fake", "node", List.of("a", "c"), env, null)));
        assertNotEquals(McpTrustStore.hash(first),
            McpTrustStore.hash(new McpServerConfig("fake", "node", List.of("a", "b"),
                Map.of("A_KEY", "1", "B_KEY", "9"), null)));
        assertNotEquals(McpTrustStore.hash(first),
            McpTrustStore.hash(new McpServerConfig("fake", "node.exe", List.of("a", "b"), env, null)));
    }

    @Test
    void toolHashIgnoresSchemaKeyOrderAndTracksAnnotationsAndDescription() throws Exception {
        McpToolInfo first = tool(mapper.readTree(
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"),
            mapper.readTree("{\"readOnlyHint\":true,\"destructiveHint\":false}"));
        // schema 与 annotations 都是内容相同、字段顺序不同 → 规范化后哈希一致
        McpToolInfo reordered = tool(mapper.readTree(
            "{\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}},\"type\":\"object\"}"),
            mapper.readTree("{\"destructiveHint\":false,\"readOnlyHint\":true}"));
        assertEquals(McpTrustStore.hash(first), McpTrustStore.hash(reordered));

        assertNotEquals(McpTrustStore.hash(first),
            McpTrustStore.hash(new McpToolInfo("echo", first.description(), first.inputSchema(), true,
                mapper.readTree("{\"readOnlyHint\":false}"))));
        assertNotEquals(McpTrustStore.hash(first), McpTrustStore.hash(new McpToolInfo("echo", "改写过的说明",
            first.inputSchema(), true, first.annotations())));
    }

    @Test
    void trustPinAllowRevokeLifecycle() {
        McpTrustStore store = new McpTrustStore(mapper, dir.resolve("trust.json"));
        McpServerConfig config = new McpServerConfig("fake", "node", List.of("s.mjs"), Map.of(), null);
        String hash = McpTrustStore.hash(config);

        store.trust(workspace, "fake", hash);
        var trusted = store.record(workspace, "fake").orElseThrow();
        assertEquals(hash, trusted.configHash());
        assertTrue(trusted.tools().isEmpty());

        // 配置哈希不匹配的钉住被拒绝
        Map<String, McpTrustStore.ToolPin> pins =
            Map.of("echo", new McpTrustStore.ToolPin("sha256:x", false));
        assertFalse(store.pinTools(workspace, "fake", "sha256:other", pins));
        // 未信任的服务器不钉
        assertFalse(store.pinTools(workspace, "ghost", hash, pins));
        assertTrue(store.pinTools(workspace, "fake", hash, pins));
        assertEquals(Map.of("echo", new McpTrustStore.ToolPin("sha256:x", false)),
            store.record(workspace, "fake").orElseThrow().tools());

        // 已钉住后不能被观测悄悄覆盖（S2 不再重钉）
        assertFalse(store.pinTools(workspace, "fake", hash,
            Map.of("echo", new McpTrustStore.ToolPin("sha256:changed", false))));
        assertEquals("sha256:x", store.record(workspace, "fake").orElseThrow().tools().get("echo").hash());

        assertTrue(store.allowTool(workspace, "fake", "write_note", "sha256:y"));
        assertEquals(new McpTrustStore.ToolPin("sha256:y", true),
            store.record(workspace, "fake").orElseThrow().tools().get("write_note"));
        // 没有信任记录时 allowTool 拒绝
        assertFalse(store.allowTool(workspace, "ghost", "x", "sha256:z"));

        store.revoke(workspace, "fake");
        assertTrue(store.record(workspace, "fake").isEmpty());
        // 幂等：重复撤销不抛
        store.revoke(workspace, "fake");
    }

    @Test
    void retrustingSameCommandKeepsPinsButNewCommandResetsThem() {
        McpTrustStore store = new McpTrustStore(mapper, dir.resolve("trust.json"));
        String hash = McpTrustStore.hash(
            new McpServerConfig("fake", "node", List.of("s.mjs"), Map.of(), null));
        store.trust(workspace, "fake", hash);
        store.pinTools(workspace, "fake", hash, Map.of("echo", new McpTrustStore.ToolPin("sha256:x", false)));

        store.trust(workspace, "fake", hash);
        assertEquals("sha256:x", store.record(workspace, "fake").orElseThrow().tools().get("echo").hash());

        store.trust(workspace, "fake", "sha256:new-config");
        assertTrue(store.record(workspace, "fake").orElseThrow().tools().isEmpty());
    }

    @Test
    void persistsAcrossInstancesAndWorkspacesAreIsolated() throws Exception {
        Path file = dir.resolve("nested").resolve("mcp-trust.json");
        McpTrustStore store = new McpTrustStore(mapper, file);
        String hash = "sha256:config";
        store.trust(workspace, "fake", hash);
        store.allowTool(workspace, "fake", "echo", "sha256:tool");
        Path otherWorkspace = dir.resolve("other-workspace");
        store.trust(otherWorkspace, "fake", "sha256:other");

        McpTrustStore reloaded = new McpTrustStore(mapper, file);
        var trusted = reloaded.record(workspace, "fake").orElseThrow();
        assertEquals("sha256:config", trusted.configHash());
        assertEquals(new McpTrustStore.ToolPin("sha256:tool", true), trusted.tools().get("echo"));
        assertTrue(trusted.trustedAt().isAfter(Instant.EPOCH));
        assertEquals("sha256:other", reloaded.record(otherWorkspace, "fake").orElseThrow().configHash());

        reloaded.revoke(workspace, "fake");
        assertTrue(new McpTrustStore(mapper, file).record(workspace, "fake").isEmpty());
        assertEquals("sha256:other",
            new McpTrustStore(mapper, file).record(otherWorkspace, "fake").orElseThrow().configHash());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void corruptFileDegradesToEmptyButWritesStillWork() throws Exception {
        Path file = dir.resolve("mcp-trust.json");
        Files.writeString(file, "{ 这不是 JSON");
        McpTrustStore store = new McpTrustStore(mapper, file);
        assertTrue(store.record(workspace, "fake").isEmpty());

        store.trust(workspace, "fake", "sha256:again");
        assertEquals("sha256:again",
            new McpTrustStore(mapper, file).record(workspace, "fake").orElseThrow().configHash());
    }

    @Test
    void unknownVersionDegradesToEmpty() throws Exception {
        Path file = dir.resolve("mcp-trust.json");
        Files.writeString(file, "{\"version\":99,\"servers\":{\"x\":{\"fake\":{\"configHash\":\"sha256:y\"}}}}");
        McpTrustStore store = new McpTrustStore(mapper, file);
        assertTrue(store.record(dir.resolve("x"), "fake").isEmpty());
    }

    @Test
    void nullFileKeepsEverythingInMemory() {
        McpTrustStore store = new McpTrustStore(mapper, null);
        store.trust(workspace, "fake", "sha256:mem");
        store.pinTools(workspace, "fake", "sha256:mem", Map.of("echo", new McpTrustStore.ToolPin("sha256:x", false)));
        store.allowTool(workspace, "fake", "echo", "sha256:x");
        assertEquals(new McpTrustStore.ToolPin("sha256:x", true),
            store.record(workspace, "fake").orElseThrow().tools().get("echo"));
        store.revoke(workspace, "fake");
        assertTrue(store.record(workspace, "fake").isEmpty());
    }

    @Test
    void abbreviateKeepsPrefixAndFirstTwelveHexChars() {
        String hash = "sha256:0123456789abcdef0123";
        assertEquals("sha256:0123456789ab", McpTrustStore.abbreviate(hash));
        assertEquals("", McpTrustStore.abbreviate(null));
    }

    private McpToolInfo tool(Object schema, Object annotations) throws Exception {
        return new McpToolInfo("echo", "Echo the given text back",
            mapper.readTree(mapper.writeValueAsString(schema)), true,
            mapper.readTree(mapper.writeValueAsString(annotations)));
    }
}
