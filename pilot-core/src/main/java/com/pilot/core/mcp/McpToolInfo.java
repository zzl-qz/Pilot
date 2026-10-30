package com.pilot.core.mcp;

import tools.jackson.databind.JsonNode;

/**
 * 服务器声明的一个工具。{@code readOnlyHint} 来自 MCP 的 annotations，只作为权限分级的提示：
 * 服务器自称只读才放行，缺省一律按「会改动」处理。
 */
public record McpToolInfo(String name, String description, JsonNode inputSchema, boolean readOnlyHint) {
    public McpToolInfo {
        description = description == null ? "" : description.strip();
    }
}
