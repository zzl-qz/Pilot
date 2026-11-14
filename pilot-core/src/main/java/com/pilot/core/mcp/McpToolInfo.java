package com.pilot.core.mcp;

import tools.jackson.databind.JsonNode;

/**
 * 服务器声明的一个工具。{@code readOnlyHint} 来自 MCP 的 annotations，默认不可信：
 * 只有显式本地信任（用户批准并信任，或用户级覆盖策略）可以用于只读放行。
 *
 * <p>{@code annotations} 保留服务器原始声明，用于信任模型的工具哈希钉住：
 * 事后翻转 {@code readOnlyHint} 或改写 description/schema 都会被哈希漂移发现。
 */
public record McpToolInfo(String name, String description, JsonNode inputSchema, boolean readOnlyHint,
    JsonNode annotations) {
    public McpToolInfo {
        description = description == null ? "" : description.strip();
    }
}
