package com.pilot.server.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.mcp.McpArgsParser;
import com.pilot.core.mcp.McpException;
import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpServerConfig;
import com.pilot.core.mcp.McpToolInfo;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.SessionLookup;
import com.pilot.server.config.AgentProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP 服务器配置管理（设置 → MCP 页签的后端）。
 *
 * <p>写入口只操作用户级 {@code .pilot/mcp.json}；工作区 {@code .mcp.json} 属于仓库内容，
 * 只读展示并标注来源。同名时工作区覆盖用户级（与 McpManager 的合并口径一致）。
 *
 * <p>POST 只写配置不建立信任：新服务器照旧走首次启动审批——「能配」不等于「能跑」，
 * 信任由审批卡上的「批准并信任」单独落库。
 */
@RestController
@RequestMapping("/api/sessions/{id}/mcp/servers")
public class McpConfigController {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final SessionService sessions;
    private final AgentProperties agentProperties;
    private final ObjectMapper mapper;
    private final ObjectProvider<McpManager> mcpManager;

    public McpConfigController(SessionService sessions, AgentProperties agentProperties, ObjectMapper mapper,
        ObjectProvider<McpManager> mcpManager) {
        this.sessions = sessions;
        this.agentProperties = agentProperties;
        this.mapper = mapper;
        this.mcpManager = mcpManager;
    }

    /** 合并视图：用户级 + 工作区，带来源、运行状态、工具数与信任态。 */
    @GetMapping
    public List<ServerPayload> servers(@PathVariable String id) {
        McpManager manager = mcpManager.getIfAvailable();
        if (manager == null) {
            return List.of();
        }
        Path workspace = workspace(id);
        List<ServerPayload> result = new ArrayList<>();
        for (McpServerConfig config : manager.servers(workspace).values()) {
            List<McpToolInfo> known = manager.knownTools(workspace, config.name());
            String source = manager.definedInUserConfig(config.name()) ? "user" : "workspace";
            result.add(new ServerPayload(config.name(), source, commandLine(config), config.command(),
                config.usable(), manager.running(workspace, config.name()), manager.failure(workspace, config.name()),
                config.unsupported(), known.size(), known.stream().map(McpToolInfo::name).limit(80).toList(),
                manager.trustState(workspace, config.name()),
                manager.trustNote(workspace, config.name())));
        }
        return List.copyOf(result);
    }

    /** 添加或覆盖用户级条目。args 支持空格简写或 JSON 数组（见 McpArgsParser）。 */
    @PostMapping
    public ServerPayload add(@PathVariable String id, @RequestBody AddRequest request) {
        McpManager manager = required();
        String name = request.name() == null ? "" : request.name().strip();
        if (!NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "名称只能包含字母、数字、点、下划线和连字符，长度 1–64");
        }
        String command = request.command() == null ? "" : request.command().strip();
        if (command.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command 不能为空，例如 npx / node / uvx");
        }
        if (request.env() != null && request.env().values().stream().anyMatch(v -> v == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "env 的值不能为 null");
        }
        List<String> args;
        try {
            args = McpArgsParser.parse(request.args());
        } catch (McpException e) {
            // 解析错误直接把带示例的文案给用户
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Path workspace = workspace(id);

        // POST 只写用户级，不触碰工作区 .mcp.json；工作区已有同名条目（会覆盖用户级）时拒绝，避免写下去不生效
        McpServerConfig merged = manager.servers(workspace).get(name);
        if (merged != null && !manager.definedInUserConfig(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "工作区 .mcp.json 里已有同名服务器 " + name + "（会覆盖用户级配置）；请改名或先从工作区配置中移除");
        }
        writeUserEntry(name, command, args, request.env());
        McpServerConfig config = manager.servers(workspace).get(name);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入后读取不到配置，请检查数据目录");
        }
        return new ServerPayload(config.name(), "user", commandLine(config), config.command(), config.usable(),
            manager.running(workspace, config.name()), null, config.unsupported(), 0, List.of(),
            manager.trustState(workspace, config.name()), null);
    }

    /** 删除用户级条目；工作区条目提示手工处理，两边都没有则 404。 */
    @DeleteMapping("/{name}")
    public void delete(@PathVariable String id, @PathVariable String name) {
        McpManager manager = required();
        Path workspace = workspace(id);
        boolean inMergedView = manager.servers(workspace).get(name) != null;
        if (inMergedView && !manager.definedInUserConfig(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "服务器 " + name + " 定义在工作区 .mcp.json 里，请直接编辑该文件");
        }
        if (!inMergedView) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有名为 " + name + " 的 MCP 服务器");
        }
        try {
            ObjectNode root = (ObjectNode)manager.readUserConfig();
            JsonNode servers = root.path("mcpServers");
            if (servers.isObject() && servers.has(name)) {
                ((ObjectNode)servers).remove(name);
                manager.saveUserConfig(root);
            }
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入用户级配置失败：" + e.getMessage());
        }
    }

    // ————— 内部 —————

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

    private void writeUserEntry(String name, String command, List<String> args, Map<String, String> env) {
        McpManager manager = required();
        try {
            ObjectNode root = (ObjectNode)manager.readUserConfig();
            ObjectNode servers = root.path("mcpServers").isObject() ? (ObjectNode)root.path("mcpServers")
                : mapper.createObjectNode();
            if (!servers.equals(root.path("mcpServers"))) {
                root.set("mcpServers", servers);
            }
            ObjectNode entry = mapper.createObjectNode();
            entry.put("command", command);
            if (!args.isEmpty()) {
                entry.set("args", mapper.valueToTree(args));
            }
            Map<String, String> cleanEnv = new LinkedHashMap<>();
            if (env != null) {
                env.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                        cleanEnv.put(key.strip(), value);
                    }
                });
            }
            if (!cleanEnv.isEmpty()) {
                entry.set("env", mapper.valueToTree(cleanEnv));
            }
            servers.set(name, entry);
            manager.saveUserConfig(root);
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入用户级配置失败：" + e.getMessage());
        }
    }

    private static String commandLine(McpServerConfig config) {
        if (config.command() == null) {
            return "";
        }
        return config.args().isEmpty() ? config.command() : config.command() + " " + String.join(" ", config.args());
    }

    public record AddRequest(String name, String command, String args, Map<String, String> env) {
    }

    public record ServerPayload(String name, String source, String command, String commandOnly, boolean usable,
        boolean running, String failure, String unsupported, int toolCount, List<String> tools, String trustState,
        String trustNote) {
    }
}
