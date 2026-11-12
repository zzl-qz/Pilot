package com.pilot.core.mcp;

import tools.jackson.databind.JsonNode;

/**
 * 服务器声明的一个工具。{@code readOnlyHint} 来自 MCP 的 annotations，只作为权限分级的提示：
 * 默认不可信，只有显式本地信任或覆盖策略可以用于只读放行。
 */
public record McpToolInfo(String name, String description, JsonNode inputSchema, boolean readOnlyHint) {
    public McpToolInfo {
        description = description == null ? "" : description.strip();
    }
}
