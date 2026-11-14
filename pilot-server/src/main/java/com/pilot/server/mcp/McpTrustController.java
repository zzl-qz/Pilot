package com.pilot.server.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpServerConfig;
import com.pilot.core.mcp.McpToolInfo;
import com.pilot.core.mcp.McpTrustStore;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.SessionLookup;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.tool.CapabilitiesController;

import lombok.RequiredArgsConstructor;

/**
 * MCP 信任管理：审批卡的「批准并信任服务器」「始终允许此工具」落 сюда，
 * 撤销入口在设置 → MCP 页签。workspace 从会话解析，不接受前端传路径——
 * 信任记录按（工作区,服务器名）键控，键必须来自会话登记的真实工作区。
 *
 * <p>哈希一律由后端从当前配置/观测计算，前端只传名字；POST 信任不等同于启动审批放行，
 * 是否放行由 McpTrustPolicy 在下一次权限检查时判断。
 */
@RestController
@RequestMapping("/api/sessions/{id}/mcp/trust")
@RequiredArgsConstructor
public class McpTrustController {
    private final SessionService sessions;
    private final AgentProperties agentProperties;
    private final org.springframework.beans.factory.ObjectProvider<McpManager> mcpManager;

    /** 信任视图：审批卡与设置页签共享的状态判定。 */
    @GetMapping
    public List<TrustedServer> trust(@PathVariable String id) {
        McpManager manager = mcpManager.getIfAvailable();
        if (manager == null || manager.trustStore() == null) {
            return List.of();
        }
        Path workspace = workspace(id);
        Map<String, McpServerConfig> servers = manager.servers(workspace);
        return servers.values().stream()
            .map(config -> trustedServer(manager, workspace, config))
            .toList();
    }

    @PostMapping
    public TrustResponse resolve(@PathVariable String id, @RequestBody TrustRequest request) {
        McpManager manager = required();
        if (manager.trustStore() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP 信任记录未启用");
        }
        if (request.action() == null || request.action().isBlank() || request.server() == null
            || request.server().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action 与 server 必填");
        }
        Path workspace = workspace(id);
        String server = request.server().strip();
        switch (request.action()) {
            case "trust" -> {
                McpServerConfig config = requireConfig(manager, workspace, server);
                manager.trustStore().trust(workspace, server, McpTrustStore.hash(config));
                boolean pinned = manager.pinIfRunning(workspace, server);
                return new TrustResponse("trust", server, pinned ? "TRUSTED" : "TRUSTED_UNPINNED");
            }
            case "allow-tool" -> {
                String tool = request.tool() == null ? "" : request.tool().strip();
                if (tool.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allow-tool 需要 tool");
                }
                requireConfig(manager, workspace, server);
                if (!manager.running(workspace, server)) {
                    // 未运行没有观测哈希可钉；引导先经 mcp_tools 启动（那一步有审批护栏）
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "服务器未在运行，无法钉住工具定义；先通过 mcp_tools 启动并批准后再「始终允许」");
                }
                McpToolInfo observed = manager.knownTools(workspace, server).stream()
                    .filter(info -> info.name().equals(tool)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "服务器 " + server + " 没有工具 " + tool));
                if (!manager.trustStore().allowTool(workspace, server, tool, McpTrustStore.hash(observed))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该服务器还没有信任记录；先「批准并信任服务器」");
                }
                return new TrustResponse("allow-tool", server, "TRUSTED");
            }
            case "revoke" -> {
                manager.trustStore().revoke(workspace, server);
                return new TrustResponse("revoke", server, "UNTRUSTED");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "未知 action：" + request.action() + "（支持 trust / allow-tool / revoke）");
        }
    }

    /** 与 {@link CapabilitiesController} 相同的 workspace 解析口径。 */
    private Path workspace(String sessionId) {
        Session session = SessionLookup.require(sessions, sessionId);
        String workspace = session.workspace() == null || session.workspace().isBlank()
            ? agentProperties.workspace() : session.workspace();
        return Path.of(workspace);
    }

    private McpManager required() {
        McpManager manager = mcpManager.getIfAvailable();
        if (manager == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP 未启用");
        }
        return manager;
    }

    private static McpServerConfig requireConfig(McpManager manager, Path workspace, String server) {
        McpServerConfig config = manager.servers(workspace).get(server);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有名为 " + server + " 的 MCP 服务器");
        }
        if (!config.usable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "MCP 服务器 " + server + " 不可用：" + config.unsupported());
        }
        return config;
    }

    public static String trustState(McpManager manager, Path workspace, McpServerConfig config) {
        McpTrustStore store = manager.trustStore();
        if (store == null) {
            return "UNTRUSTED";
        }
        McpTrustStore.ServerTrust record = store.record(workspace, config.name()).orElse(null);
        if (record == null) {
            return "UNTRUSTED";
        }
        return record.configHash().equals(McpTrustStore.hash(config))
            ? (record.tools().isEmpty() ? "TRUSTED_UNPINNED" : "TRUSTED")
            : "STALE";
    }

    private static TrustedServer trustedServer(McpManager manager, Path workspace, McpServerConfig config) {
        return new TrustedServer(config.name(), trustState(manager, workspace, config), manager.trustNote(workspace,
            config.name()));
    }

    public record TrustRequest(String action, String server, String tool) {
    }

    public record TrustResponse(String action, String server, String state) {
    }

    public record TrustedServer(String server, String state, String note) {
    }
}
