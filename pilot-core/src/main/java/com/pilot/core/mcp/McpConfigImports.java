package com.pilot.core.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * 解析用户粘贴的 MCP 服务器 JSON 配置（设置 → MCP 的「粘贴 JSON」导入）。
 *
 * <p>同一文本域支持两种格式（对齐 zocde 的交互）：
 * <ul>
 *   <li>标准包裹：{@code {"mcpServers": {"名字": {"command": "npx", …}}}}</li>
 *   <li>顶层映射：{@code {"名字": {"command": "npx", …}}}</li>
 * </ul>
 *
 * <p>只做结构归一化（两种格式解析成同一映射、保持输入顺序、基本结构校验）；
 * 名称/命令等业务校验由调用方（导入接口）完成，写盘沿用 McpManager 的用户级配置读写。
 */
public final class McpConfigImports {
    private McpConfigImports() {
    }

    /**
     * @param json 用户粘贴的文本；空白视为错误（导入必须有内容）
     * @return 服务器名 → 条目原文（保持输入顺序）；条目内容原样保留，含 args/env/type 等字段
     */
    public static Map<String, JsonNode> parse(String json) {
        String text = json == null ? "" : json.strip();
        if (text.isEmpty()) {
            throw new McpException("配置内容不能为空。支持直接粘贴 {\"服务器名\": {…}} "
                + "或 {\"mcpServers\": {\"服务器名\": {…}}}。");
        }
        JsonNode root;
        try {
            root = McpJson.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new McpException("JSON 解析失败（" + e.getMessage() + "）。支持直接粘贴 "
                + "{\"服务器名\": {…}} 或 {\"mcpServers\": {\"服务器名\": {…}}}。");
        }
        if (!root.isObject()) {
            throw new McpException("配置必须是 JSON 对象。支持直接粘贴 {\"服务器名\": {…}} "
                + "或 {\"mcpServers\": {\"服务器名\": {…}}}。");
        }
        // 有 mcpServers 字段且为对象 → 标准包裹格式；否则顶层按服务器映射处理
        JsonNode wrapped = root.path("mcpServers");
        JsonNode entries = wrapped.isObject() ? wrapped : root;
        Map<String, JsonNode> servers = new LinkedHashMap<>();
        for (String name : entries.propertyNames()) {
            JsonNode entry = entries.path(name);
            if (!entry.isObject()) {
                throw new McpException("服务器 " + name + " 的配置必须是对象：\""
                    + name + "\": {\"command\": \"…\", \"args\": […]}。");
            }
            servers.put(name, entry);
        }
        if (servers.isEmpty()) {
            throw new McpException("没有可导入的服务器。支持直接粘贴 {\"服务器名\": {…}} "
                + "或 {\"mcpServers\": {\"服务器名\": {…}}}。");
        }
        return servers;
    }
}
