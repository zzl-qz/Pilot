package com.pilot.server.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.mcp.McpArgsParser;
import com.pilot.core.mcp.McpConfigImports;
import com.pilot.core.mcp.McpException;
import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpServerConfig;
import com.pilot.core.mcp.McpToolInfo;
import com.pilot.core.mcp.McpTrustStore;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.util.Texts;
import com.pilot.server.api.ApiErrors;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.session.lookup.SessionLookup;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP 服务器配置管理（设置 → MCP 页签的后端）。
 *
 * <p>写入口只操作用户级 {@code mcp.json}；工作区 {@code .mcp.json} 属于仓库内容，
 * 只读展示并标注来源。同名时工作区覆盖用户级（与 McpManager 的合并口径一致），
 * 所以工作区已有同名条目时写用户级不会生效，直接拒绝。
 *
 * <p>POST 只写配置不建立信任：新服务器照旧走首次启动审批——「能配」不等于「能跑」，
 * 信任由审批卡上的「批准并信任」单独落库。粘贴导入只接受 command/args/env/type 白名单，
 * 不允许借导入创建 {@code pilotPermissions} 这类本地授权字段。
 */
@RestController
@RequestMapping("/api/sessions/{id}/mcp/servers")
public class McpConfigController {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> IMPORT_FIELDS = Set.of("command", "args", "env", "type", "url", "headers");
    private static final Set<String> REMOTE_TYPES = Set.of("http", "streamable-http", "streamable_http",
        "streamablehttp", "sse");
    /** 与 McpToolsTool.MAX_LISTED 同一展示口径。 */
    private static final int MAX_LISTED_TOOLS = 80;

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
        Path workspace = workspace(id);
        McpManager manager = mcpManager.getIfAvailable();
        if (manager == null) {
            return List.of();
        }
        List<ServerPayload> result = new ArrayList<>();
        for (McpServerConfig config : manager.servers(workspace).values()) {
            List<McpToolInfo> known = manager.knownTools(workspace, config.name());
            String source = manager.definedInWorkspaceConfig(workspace, config.name()) ? "workspace" : "user";
            result.add(new ServerPayload(config.name(), source, commandLine(config), config.command(), config.usable(),
                manager.running(workspace, config.name()), manager.failure(workspace, config.name()),
                config.unsupported(), known.size(),
                known.stream().map(McpToolInfo::name).limit(MAX_LISTED_TOOLS).toList(),
                manager.trustState(workspace, config.name()), manager.trustNote(workspace, config.name()),
                McpTrustStore.hash(config)));
        }
        return List.copyOf(result);
    }

    /** 添加或覆盖用户级条目。支持本地 stdio（command + args + env）及远程 HTTP / SSE（url + headers）。 */
    @PostMapping
    public ServerPayload add(@PathVariable String id, @RequestBody(required = false) AddRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        McpManager manager = required();
        String name = request.name() == null ? "" : request.name().strip();
        if (!NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名称只能包含字母、数字、点、下划线和连字符，长度 1–64");
        }
        String rawTransport = request.transport() == null ? "" : request.transport().strip().toLowerCase(Locale.ROOT);
        String url = request.url() == null ? "" : request.url().strip();
        String command = request.command() == null ? "" : request.command().strip();
        boolean remote = REMOTE_TYPES.contains(rawTransport) || (!url.isEmpty() && command.isEmpty());
        Path workspace = workspace(id);
        if (manager.definedInWorkspaceConfig(workspace, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "工作区 .mcp.json 里已有同名服务器 " + name + "（会覆盖用户级配置）；请改名或先从工作区配置中移除");
        }
        if (remote) {
            String urlError = McpServerConfig.validateRemoteUrl(url);
            if (urlError != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, urlError);
            }
            if (request.headers() != null && request.headers().values().stream().anyMatch(value -> value == null)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "headers 的值不能为 null");
            }
            String normalizedType = "sse".equals(rawTransport) ? McpServerConfig.TRANSPORT_SSE
                : McpServerConfig.TRANSPORT_HTTP;
            Map<String, String> headers = new LinkedHashMap<>();
            if (request.headers() != null) {
                request.headers().forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        headers.put(key.strip(), value);
                    }
                });
            }
            try {
                manager.updateUserConfig(root -> {
                    ObjectNode servers = serversNode(root);
                    JsonNode existing = servers.path(name);
                    ObjectNode entry =
                        existing.isObject() ? ((ObjectNode)existing).deepCopy() : mapper.createObjectNode();
                    entry.remove("command");
                    entry.remove("args");
                    entry.remove("env");
                    entry.put("type", normalizedType);
                    entry.put("url", url);
                    if (headers.isEmpty()) {
                        entry.remove("headers");
                    } else {
                        entry.set("headers", mapper.valueToTree(headers));
                    }
                    servers.set(name, entry);
                    return null;
                });
            } catch (McpException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "写入用户级配置失败：" + e.getMessage());
            }
        } else {
            if (command.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command 不能为空，例如 npx / node / uvx");
            }
            if (request.env() != null && request.env().values().stream().anyMatch(value -> value == null)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "env 的值不能为 null");
            }
            List<String> args;
            try {
                args = McpArgsParser.parse(request.args());
            } catch (McpException e) {
                // 解析错误直接把带示例的文案给用户
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            Map<String, String> env = new LinkedHashMap<>();
            if (request.env() != null) {
                request.env().forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        env.put(key.strip(), value);
                    }
                });
            }
            try {
                manager.updateUserConfig(root -> {
                    ObjectNode servers = serversNode(root);
                    JsonNode existing = servers.path(name);
                    // 覆盖时保留 pilotPermissions 等手写字段，只替换这次表单管理的 command/args/env；
                    // 旧的 url/headers/非 stdio type 不能和新命令混在一起
                    ObjectNode entry =
                        existing.isObject() ? ((ObjectNode)existing).deepCopy() : mapper.createObjectNode();
                    entry.remove("url");
                    entry.remove("headers");
                    if (!entry.path("type").isMissingNode() && !"stdio".equals(entry.path("type").asString())) {
                        entry.remove("type");
                    }
                    entry.put("command", command);
                    if (args.isEmpty()) {
                        entry.remove("args");
                    } else {
                        entry.set("args", mapper.valueToTree(args));
                    }
                    if (env.isEmpty()) {
                        entry.remove("env");
                    } else {
                        entry.set("env", mapper.valueToTree(env));
                    }
                    servers.set(name, entry);
                    return null;
                });
            } catch (McpException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "写入用户级配置失败：" + e.getMessage());
            }
        }
        McpServerConfig config = manager.servers(workspace).get(name);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入后读取不到配置，请检查数据目录");
        }
        return new ServerPayload(config.name(), "user", commandLine(config), config.command(), config.usable(),
            manager.running(workspace, config.name()), null, config.unsupported(), 0, List.of(),
            manager.trustState(workspace, config.name()), null, McpTrustStore.hash(config));
    }

    /** 删除用户级条目；工作区条目提示手工处理，两边都没有则 404。删除后同时撤销信任。 */
    @DeleteMapping("/{name}")
    public void delete(@PathVariable String id, @PathVariable String name) {
        McpManager manager = required();
        Path workspace = workspace(id);
        if (manager.definedInWorkspaceConfig(workspace, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "服务器 " + name + " 定义在工作区 .mcp.json 里，请直接编辑该文件");
        }
        if (!manager.userConfigReadable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户级 MCP 配置无法解析，请先修复或备份后重试；本次未删除");
        }
        try {
            manager.updateUserConfig(root -> {
                JsonNode servers = root.path("mcpServers");
                if (!servers.isObject() || !servers.has(name)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有名为 " + name + " 的 MCP 服务器");
                }
                ((ObjectNode)servers).remove(name);
                return null;
            });
        } catch (McpException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "写入用户级配置失败：" + e.getMessage());
        }
        if (manager.trustStore() != null) {
            manager.trustStore().revoke(workspace, name);
        }
    }

    /**
     * 批量导入（粘贴 JSON）：支持 {"服务器名": {…}} 与 {"mcpServers": {…}} 两种格式。
     *
     * <p>整批原子：任一条目非法、或与工作区同名即全部拒绝；与用户级同名则覆盖。
     * 只接受 stdio 字段白名单，导入不会创建 pilotPermissions 等本地授权。
     */
    @PostMapping("/import")
    public ImportResponse importServers(@PathVariable String id, @RequestBody(required = false) ImportRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        McpManager manager = required();
        Path workspace = workspace(id);
        Map<String, JsonNode> entries;
        try {
            entries = McpConfigImports.parse(request.config());
        } catch (McpException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Map<String, ObjectNode> sanitized = new LinkedHashMap<>();
        List<String> overwritten = new ArrayList<>();
        for (Map.Entry<String, JsonNode> item : entries.entrySet()) {
            String name = item.getKey();
            if (!NAME.matcher(name).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "服务器名 " + name + " 不合法：只能包含字母、数字、点、下划线和连字符，长度 1–64");
            }
            if (manager.definedInWorkspaceConfig(workspace, name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "工作区 .mcp.json 里已有同名服务器 " + name + "（会覆盖用户级配置）；请改名或先从工作区配置中移除；" + "本次导入未写入任何内容");
            }
            sanitized.put(name, sanitizeImport(name, item.getValue()));
            if (manager.definedInUserConfig(name)) {
                overwritten.add(name);
            }
        }
        if (!overwritten.isEmpty() && !Boolean.TRUE.equals(request.confirmOverwrite())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "导入会覆盖同名用户级配置：" + String.join("、", overwritten) + "；确认后再次导入；本次未写入");
        }
        try {
            manager.updateUserConfig(root -> {
                ObjectNode servers = serversNode(root);
                sanitized.forEach(servers::set);
                return null;
            });
        } catch (McpException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "写入用户级配置失败：" + e.getMessage());
        }
        return new ImportResponse(List.copyOf(entries.keySet()), List.copyOf(overwritten));
    }

    /**
     * 测试连接：真正拉起服务器进程（执行配置里的 command）并读取工具清单。
     * 用户在管理界面点这个按钮即视为主动授权本次启动（与对话内「批准」同语义）；
     * 但不建立信任——信任仍由「信任此服务器」单独落库，模型调用照常按决策矩阵判定。
     *
     * <p>启动失败不是异常而是预期内的结果，用 {@code ok=false} 返回原因；npx 首次拉包可能较慢。
     */
    @PostMapping("/{name}/probe")
    public ProbeResponse probe(@PathVariable String id, @PathVariable String name) {
        McpManager manager = required();
        Path workspace = workspace(id);
        McpServerConfig config = manager.servers(workspace).get(name);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有名为 " + name + " 的 MCP 服务器");
        }
        if (!config.usable()) {
            return new ProbeResponse(false, config.unsupported() == null ? "缺少 command" : config.unsupported(), false,
                List.of());
        }
        try {
            List<McpToolInfo> tools = manager.tools(workspace, name);
            return new ProbeResponse(true, "", manager.running(workspace, name),
                tools.stream().limit(80).map(ProbeTool::of).toList());
        } catch (McpException e) {
            return new ProbeResponse(false, e.getMessage(), false, List.of());
        }
    }

    // ————— 内部 —————

    /** 把 ResponseStatusException 的原因带进响应体；默认错误页只给状态短语，界面上看不到原因。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException failure) {
        return ApiErrors.message(failure.getStatusCode(), failure.getReason());
    }

    /** 导入白名单：接受 stdio（command/args/env）与远程 HTTP/SSE（url/headers/type），拒绝本地授权字段与未知字段。 */
    private ObjectNode sanitizeImport(String name, JsonNode entry) {
        for (String field : entry.propertyNames()) {
            if (!IMPORT_FIELDS.contains(field)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 含不允许导入的字段 " + field
                    + "；只支持 command/args/env/url/headers/type，pilotPermissions 等本地授权请手动配置");
            }
        }
        JsonNode typeNode = entry.path("type");
        String type = "";
        if (!typeNode.isMissingNode()) {
            if (!typeNode.isTextual()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 的 type 必须是字符串");
            }
            type = typeNode.asString("").strip().toLowerCase(Locale.ROOT);
            if (!"stdio".equals(type) && !REMOTE_TYPES.contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "服务器 " + name + " 的传输类型 " + typeNode.asString() + " 不支持（仅支持 stdio / http / sse）");
            }
        }
        JsonNode urlNode = entry.path("url");
        JsonNode commandNode = entry.path("command");
        boolean remote = REMOTE_TYPES.contains(type)
            || (!urlNode.isMissingNode() && (commandNode.isMissingNode() || commandNode.asString("").isBlank()));
        if (remote) {
            if (!commandNode.isMissingNode()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "服务器 " + name + " 不能同时配置 command 与远程 url");
            }
            if (!urlNode.isTextual() || urlNode.asString("").isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 缺少远程 url");
            }
            String url = urlNode.asString().strip();
            String urlError = McpServerConfig.validateRemoteUrl(url);
            if (urlError != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + "：" + urlError);
            }
            ObjectNode clean = mapper.createObjectNode();
            clean.put("type", "sse".equals(type) ? McpServerConfig.TRANSPORT_SSE : McpServerConfig.TRANSPORT_HTTP);
            clean.put("url", url);
            JsonNode headers = entry.path("headers");
            if (!headers.isMissingNode()) {
                if (!headers.isObject()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 的 headers 必须是字符串映射");
                }
                for (String key : headers.propertyNames()) {
                    if (!headers.path(key).isTextual()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "服务器 " + name + " 的 headers 值必须是字符串");
                    }
                }
                clean.set("headers", headers.deepCopy());
            }
            return clean;
        }
        if (!commandNode.isTextual() || commandNode.asString("").strip().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "服务器 " + name + " 缺少 command 或 url；示例：{\"" + name + "\": {\"command\": \"npx\", \"args\": []}}");
        }
        ObjectNode clean = mapper.createObjectNode();
        clean.put("command", commandNode.asString().strip());
        JsonNode args = entry.path("args");
        if (!args.isMissingNode()) {
            if (!args.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 的 args 必须是字符串数组");
            }
            for (JsonNode arg : args) {
                if (!arg.isTextual()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 的 args 必须是字符串数组");
                }
            }
            clean.set("args", args.deepCopy());
        }
        JsonNode env = entry.path("env");
        if (!env.isMissingNode()) {
            if (!env.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 的 env 必须是字符串映射");
            }
            for (String key : env.propertyNames()) {
                if (!env.path(key).isTextual()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器 " + name + " 的 env 值必须是字符串");
                }
            }
            clean.set("env", env.deepCopy());
        }
        if ("stdio".equals(type)) {
            clean.put("type", "stdio");
        }
        return clean;
    }

    private ObjectNode serversNode(ObjectNode root) {
        JsonNode existing = root.path("mcpServers");
        if (existing.isObject()) {
            return (ObjectNode)existing;
        }
        ObjectNode servers = mapper.createObjectNode();
        root.set("mcpServers", servers);
        return servers;
    }

    private Path workspace(String sessionId) {
        Session session = SessionLookup.require(sessions, sessionId);
        String workspace = session.workspace() == null || session.workspace().isBlank() ? agentProperties.workspace()
            : session.workspace();
        return Path.of(workspace);
    }

    private McpManager required() {
        McpManager manager = mcpManager.getIfAvailable();
        if (manager == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP 未启用");
        }
        return manager;
    }

    private static String commandLine(McpServerConfig config) {
        if (config.isRemote()) {
            return config.transport().toUpperCase(Locale.ROOT) + " " + config.url();
        }
        if (config.command() == null) {
            return "";
        }
        return config.args().isEmpty() ? config.command() : config.command() + " " + String.join(" ", config.args());
    }

    public record AddRequest(String name, String command, String args, Map<String, String> env, String transport,
        String url, Map<String, String> headers) {
        public AddRequest(String name, String command, String args, Map<String, String> env) {
            this(name, command, args, env, null, null, null);
        }
    }

    /** 粘贴导入：config 是原始 JSON 文本（两种格式见 McpConfigImports）。 */
    public record ImportRequest(String config, Boolean confirmOverwrite) {
        public ImportRequest(String config) {
            this(config, null);
        }
    }

    /** @param imported    本次写入的服务器名（含覆盖）
     *  @param overwritten 其中覆盖了既有用户级条目的名字 */
    public record ImportResponse(List<String> imported, List<String> overwritten) {
    }

    /**
     * @param source     生效来源：workspace（工作区同名会覆盖用户级）或 user
     * @param trustState UNTRUSTED / TRUSTED_UNPINNED / TRUSTED / STALE
     * @param configHash 当前配置哈希；设置页「信任」按钮原样带回
     */
    public record ServerPayload(String name, String source, String command, String commandOnly, boolean usable,
        boolean running, String failure, String unsupported, int toolCount, List<String> tools, String trustState,
        String trustNote, String configHash) {
    }

    /** 测试结果：ok=false 时 error 说明原因；tools 为启动后读到的工具清单（前 80 个）。 */
    public record ProbeResponse(boolean ok, String error, boolean running, List<ProbeTool> tools) {
    }

    /** 工具条目：名称 + 一行描述 + 是否声明只读。 */
    public record ProbeTool(String name, String description, boolean readOnly) {
        static ProbeTool of(McpToolInfo info) {
            return new ProbeTool(info.name(), Texts.oneLine(info.description(), 120), info.readOnlyHint());
        }
    }
}
