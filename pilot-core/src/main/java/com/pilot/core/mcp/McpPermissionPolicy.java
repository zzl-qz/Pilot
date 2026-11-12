package com.pilot.core.mcp;

import java.util.HashSet;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** 仅来自用户级配置且绑定相同启动配置的本地策略；服务器注解和工作区配置不能自授信任。 */
record McpPermissionPolicy(boolean trustReadOnlyHints, Set<String> readOnlyTools, Set<String> requireApprovalTools) {
    static final McpPermissionPolicy UNTRUSTED = new McpPermissionPolicy(false, Set.of(), Set.of());

    McpPermissionPolicy {
        readOnlyTools = Set.copyOf(readOnlyTools);
        requireApprovalTools = Set.copyOf(requireApprovalTools);
    }

    static McpPermissionPolicy from(JsonNode node) {
        return new McpPermissionPolicy(
            node.path("trustReadOnlyHints").isBoolean() && node.path("trustReadOnlyHints").asBoolean(false),
            names(node.path("readOnlyTools")), names(node.path("requireApprovalTools")));
    }

    boolean readOnly(McpToolInfo info) {
        return !requireApprovalTools.contains(info.name())
            && (readOnlyTools.contains(info.name()) || trustReadOnlyHints && info.readOnlyHint());
    }

    private static Set<String> names(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isString() && !item.asString().isBlank()) {
                    values.add(item.asString());
                }
            }
        }
        return values;
    }
}
