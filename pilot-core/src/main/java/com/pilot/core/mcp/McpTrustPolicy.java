package com.pilot.core.mcp;

import java.nio.file.Path;
import java.util.Optional;

/**
 * MCP 权限决策矩阵（见 docs/DESIGN-mcp-trust.md §2.1）。核心原则：
 * <strong>信任是服务器的属性，不是工具的自我声明</strong>——{@code readOnlyHint}
 * 只有在「用户已显式信任该服务器 + 配置哈希未变 + 工具哈希已钉住且匹配」时才参与免审批。
 *
 * <p>两个入口分别对应模型可见的两个工具：
 * <ul>
 *   <li>{@code mcp_tools}：行为 1–4（列出 / 查询已运行 / 启动未信任 / 启动已信任）</li>
 *   <li>{@code mcp_call}：行为 5–9（钉住且只读 / 其余全部审批 / 报错路径放行）</li>
 * </ul>
 *
 * <p>报错路径（参数不全、未配置、配置不可用）统一放行：执行只会返回 ERROR，没有副作用。
 */
final class McpTrustPolicy {
    private McpTrustPolicy() {
    }

    // ————— mcp_tools 行 1–4 —————

    /** 查询是否只读：列出配置、查询已运行或已信任服务器免审批；启动未信任服务器不是只读。 */
    static boolean listingReadOnly(McpManager manager, Path workspace, String server) {
        if (server == null) {
            return true;
        }
        McpManager.Selection selected = manager.selection(workspace, server);
        return listingReadOnly(manager, workspace, server, selected);
    }

    /** 带预拍的 Selection 快照的版本，避免 readOnly 与 execute 两次读配置。 */
    static boolean listingReadOnly(McpManager manager, Path workspace, String server, McpManager.Selection selected) {
        if (server == null) {
            return true;
        }
        McpServerConfig config = selected.config();
        if (config == null || !config.usable()) {
            return true;
        }
        return manager.running(workspace, server) || trusted(manager, workspace, server);
    }

    /**
     * 启动未信任的服务器等于执行配置里的命令，即使权限档位是完全放行（AUTO）也要先经用户批准。
     */
    static boolean listingNeedsConfirm(McpManager manager, Path workspace, String server) {
        if (server == null) {
            return false;
        }
        McpServerConfig config = manager.servers(workspace).get(server);
        if (config == null || !config.usable()) {
            return false;
        }
        return !manager.running(workspace, server) && !trusted(manager, workspace, server);
    }

    // ————— mcp_call 行 5–9 —————

    /**
     * 调用是否只读，当且仅当满足下列任一：
     * <ul>
     *   <li>动态信任：服务器有生效的信任记录、该工具已钉住、当前观测哈希与钉住值一致，
     *       且（服务器声明只读 或 用户对该工具做过「始终允许」）；</li>
     *   <li>静态本地策略：用户级配置为该服务器绑定了相同命令与工作区的 pilotPermissions，
     *       且策略判该工具只读。</li>
     * </ul>
     * 两者都是用户显式行为（审批动作/手写配置），取并集。其余情况一律按需改动处理。
     */
    static boolean callReadOnly(McpManager manager, Path workspace, String server, String tool) {
        if (server == null || tool == null) {
            return true;
        }
        McpServerConfig config = manager.servers(workspace).get(server);
        if (config == null || !config.usable()) {
            return true;
        }
        // 静态本地策略：用户级 pilotPermissions（绑定相同命令与工作区）；强制审批名单优先否决
        McpPermissionPolicy policy = manager.permissionPolicy(workspace, server);
        if (policy.requireApprovalTools().contains(tool)) {
            return false;
        }
        for (McpToolInfo info : manager.knownTools(workspace, server)) {
            if (info.name().equals(tool) && policy.readOnly(info)) {
                return true;
            }
        }
        // 动态信任：审批落库 + 哈希钉住
        Optional<McpTrustStore.ServerTrust> record = pinnedRecord(manager, workspace, server);
        if (record.isEmpty()) {
            return false;
        }
        McpToolInfo observed = null;
        for (McpToolInfo info : manager.knownTools(workspace, server)) {
            if (info.name().equals(tool)) {
                observed = info;
                break;
            }
        }
        if (observed == null) {
            return false;
        }
        McpTrustStore.ToolPin pin = record.get().tools().get(tool);
        if (pin == null || !pin.hash().equals(McpTrustStore.hash(observed))) {
            return false;
        }
        return observed.readOnlyHint() || pin.allowed();
    }

    /** 非 5/7（钉住匹配且只读/allowlist）的合法调用都要确认；报错路径不确认。 */
    static boolean callNeedsConfirm(McpManager manager, Path workspace, String server, String tool) {
        if (server == null || tool == null) {
            return false;
        }
        McpServerConfig config = manager.servers(workspace).get(server);
        if (config == null || !config.usable()) {
            return false;
        }
        return !callReadOnly(manager, workspace, server, tool);
    }

    /** 服务器是否有生效的信任记录（S1 或 S2）：已批准当前这条启动命令。 */
    static boolean trusted(McpManager manager, Path workspace, String server) {
        McpTrustStore store = manager.trustStore();
        if (store == null) {
            return false;
        }
        Optional<McpTrustStore.ServerTrust> record = store.record(workspace, server);
        if (record.isEmpty()) {
            return false;
        }
        McpServerConfig config = manager.servers(workspace).get(server);
        return config != null && config.usable()
            && record.get().configHash().equals(McpTrustStore.hash(config));
    }

    /** 记录存在、配置哈希匹配、工具已钉住（S2）。 */
    private static Optional<McpTrustStore.ServerTrust> pinnedRecord(McpManager manager, Path workspace,
        String server) {
        McpTrustStore store = manager.trustStore();
        if (store == null) {
            return Optional.empty();
        }
        Optional<McpTrustStore.ServerTrust> record = store.record(workspace, server);
        if (record.isEmpty() || record.get().tools().isEmpty()) {
            return Optional.empty();
        }
        McpServerConfig config = manager.servers(workspace).get(server);
        return config != null && config.usable()
            && record.get().configHash().equals(McpTrustStore.hash(config)) ? record : Optional.empty();
    }
}
