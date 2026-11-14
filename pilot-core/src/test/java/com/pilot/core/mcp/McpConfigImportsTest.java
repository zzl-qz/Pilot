package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 双格式粘贴导入的归一化：包裹与顶层映射等价、非法输入带格式提示。 */
class McpConfigImportsTest {

    @Test
    void wrappedAndTopLevelFormatsParseToTheSameEntries() {
        var wrapped = McpConfigImports.parse("""
            {"mcpServers": {"filesystem": {"command": "npx", "args": ["-y", "@scope/server"]}}}
            """);
        var topLevel = McpConfigImports.parse("""
            {"filesystem": {"command": "npx", "args": ["-y", "@scope/server"]}}
            """);
        assertEquals(List.of("filesystem"), List.copyOf(wrapped.keySet()));
        assertEquals(wrapped, topLevel);
        assertEquals("npx", wrapped.get("filesystem").path("command").asString());
        assertEquals(2, wrapped.get("filesystem").path("args").size());
    }

    @Test
    void multipleEntriesKeepInputOrderAndUnknownFieldsArePreserved() {
        var entries = McpConfigImports.parse("""
            {"a": {"command": "node", "type": "stdio"}, "b": {"command": "uvx", "env": {"K": "v"}}}
            """);
        assertEquals(List.of("a", "b"), List.copyOf(entries.keySet()));
        assertEquals("stdio", entries.get("a").path("type").asString());
        assertEquals("v", entries.get("b").path("env").path("K").asString());
    }

    @Test
    void blankOrNonObjectInputIsRejectedWithGuidance() {
        McpException empty = assertThrows(McpException.class, () -> McpConfigImports.parse("   "));
        assertTrue(empty.getMessage().contains("不能为空"), empty.getMessage());

        McpException array = assertThrows(McpException.class, () -> McpConfigImports.parse("[\"npx\"]"));
        assertTrue(array.getMessage().contains("JSON 对象"), array.getMessage());

        McpException broken = assertThrows(McpException.class, () -> McpConfigImports.parse("{\"a\": "));
        assertTrue(broken.getMessage().contains("解析失败"), broken.getMessage());
    }

    @Test
    void nonObjectEntryAndEmptyMappingsAreRejected() {
        McpException scalar = assertThrows(McpException.class,
            () -> McpConfigImports.parse("{\"a\": \"npx\"}"));
        assertTrue(scalar.getMessage().contains("必须是对象"), scalar.getMessage());

        McpException emptyWrapped = assertThrows(McpException.class,
            () -> McpConfigImports.parse("{\"mcpServers\": {}}"));
        assertTrue(emptyWrapped.getMessage().contains("没有可导入"), emptyWrapped.getMessage());

        McpException emptyTop = assertThrows(McpException.class, () -> McpConfigImports.parse("{}"));
        assertTrue(emptyTop.getMessage().contains("没有可导入"), emptyTop.getMessage());
    }

    @Test
    void bareSingleServerObjectIsRejectedBecauseThereIsNoName() {
        // {"command": "npx"} 的值不是对象，按顶层映射解析会失败——引导用户用映射格式起名
        McpException bare = assertThrows(McpException.class, () -> McpConfigImports.parse("{\"command\": \"npx\"}"));
        assertTrue(bare.getMessage().contains("必须是对象"), bare.getMessage());
    }
}
