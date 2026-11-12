package com.pilot.core.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 服务器的配置与生命周期。
 *
 * <p>配置来自工作区的 {@code .mcp.json}（与 Claude Code 同一格式：{@code {"mcpServers": {"名字": {"command": …}}}}）
 * 以及可选的用户级文件，同名时工作区优先。服务器按「工作区 + 名字」懒启动：
 * 模型第一次真正用到时才拉起进程，之后复用，服务关闭时统一回收。
 *
 * <p>启动一个服务器等于执行配置里的命令，因此调用方（工具层）必须把「启动」当作需要审批的动作。
 */
public final class McpManager implements AutoCloseable {
    static final Duration START_TIMEOUT = Duration.ofSeconds(40);
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper mapper;
    private final Path userConfig;
    private final Path logDir;
    private final Supplier<String> extraPath;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private boolean closed;

    /**
     * @param userConfig 用户级配置文件，可为 {@code null}
     * @param logDir     服务器 stderr 的日志目录，可为 {@code null}（丢弃）
     * @param extraPath  追加到子进程 PATH 前面的目录（内置 Node 等）；每次启动服务器时取值，
     *                   因为内置运行时在 Bean 创建之后才准备好
     */
    public McpManager(ObjectMapper mapper, Path userConfig, Path logDir, Supplier<String> extraPath) {
        this.mapper = mapper;
        this.userConfig = userConfig;
        this.logDir = logDir;
        this.extraPath = extraPath == null ? () -> "" : extraPath;
    }

    /** 某个工作区当前配置的全部服务器（按名字排序）。配置文件每次现读；已启动进程的启动配置变化须重启服务。 */
    public Map<String, McpServerConfig> servers(Path workspace) {
        Map<String, McpServerConfig> merged = new java.util.TreeMap<>();
        if (userConfig != null) {
            merged.putAll(load(userConfig));
        }
        merged.putAll(load(workspace.resolve(".mcp.json")));
        return merged;
    }

    public boolean hasServers(Path workspace) {
        return !servers(workspace).isEmpty();
    }

    record Selection(McpServerConfig config, String extraPath, McpClient client, Path workspace) {
    }

    Selection selection(Path workspace, String server) {
        Map<String, McpServerConfig> configs = servers(workspace);
        McpServerConfig config = configs.get(server);
        if (configs.values().stream()
            .anyMatch(value -> value.unsupported() != null && value.unsupported().startsWith("配置文件无法解析"))) {
            config = new McpServerConfig(server, null, List.of(), Map.of(), "配置文件无法解析，请先修复后再发现工具");
        }
        return new Selection(config, Objects.toString(extraPath.get(), ""), clients.get(key(workspace, server)),
            McpClient.canonicalWorkspace(workspace));
    }

    private static boolean current(Selection selected) {
        return selected.client() != null && selected.config() != null && selected.config().usable()
            && selected.client().matches(selected.config(), selected.extraPath())
            && selected.client().matchesWorkspace(selected.workspace());
    }

    public boolean configurationChanged(Path workspace, String server) {
        Selection selected = selection(workspace, server);
        return selected.client() != null && !current(selected);
    }

    public boolean running(Path workspace, String server) {
        Selection selected = selection(workspace, server);
        return current(selected) && selected.client().running();
    }

    /** 上一次启动失败的原因；从未失败或已恢复时为 {@code null}。 */
    public String failure(Path workspace, String server) {
        return failures.get(key(workspace, server));
    }

    /** 只有工具发现入口可以启动进程；实际调用不隐式启动或重新连接。 */
    McpClient client(Path workspace, String server) {
        return client(workspace, server, selection(workspace, server), true);
    }

    private synchronized McpClient client(Path workspace, String server, Selection expected, boolean allowStart) {
        if (closed) {
            throw new McpException("MCP 管理器已关闭");
        }
        Selection selected = selection(workspace, server);
        if (!Objects.equals(selected.config(), expected.config()) || !selected.extraPath().equals(expected.extraPath())
            || !selected.workspace().equals(expected.workspace())) {
            throw new McpException("MCP 配置在权限检查后已变化，请重新发现并确认启动配置");
        }
        McpServerConfig config = selected.config();
        if (config == null) {
            throw new McpException("没有名为 " + server + " 的 MCP 服务器；请先用 mcp_tools 查看配置");
        }
        if (!config.usable()) {
            throw new McpException(
                "MCP 服务器 " + server + " 无法使用：" + (config.unsupported() == null ? "缺少 command" : config.unsupported()));
        }
        String key = key(workspace, server);
        McpClient existing = selected.client();
        if (existing != null && !current(selected)) {
            throw new McpException("MCP 服务器 " + server + " 配置已变更，旧连接不再接受新调用；请重启 Pilot 服务后生效并重新发现工具");
        }
        if (!allowStart && (existing == null || existing != expected.client() || !existing.running())) {
            throw new McpException("MCP 连接状态已变化或尚未启动；请先用 mcp_tools 重新发现并按权限确认，未执行本次调用");
        }
        if (existing != null && existing.running()) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        Path log = logDir == null ? null : logDir.resolve(safe(server) + ".log");
        McpClient fresh = new McpClient(server, config, workspace, log, mapper, selected.extraPath());
        try {
            fresh.start(START_TIMEOUT);
            fresh.tools(LIST_TIMEOUT);
            Selection after = selection(workspace, server);
            if (!Objects.equals(after.config(), config) || !after.extraPath().equals(selected.extraPath())
                || !after.workspace().equals(selected.workspace())) {
                throw new McpException("MCP 配置在启动期间已变化，已关闭旧进程；请重新发现工具");
            }
        } catch (McpException e) {
            fresh.close();
            clients.remove(key);
            failures.put(key, e.getMessage());
            throw e;
        }
        failures.remove(key);
        clients.put(key, fresh);
        return fresh;
    }

    /** 仅返回当前配置对应的缓存；权限检查不能隐式执行远端发现。 */
    public List<McpToolInfo> knownTools(Path workspace, String server) {
        Selection selected = selection(workspace, server);
        return current(selected) && selected.client().running() ? selected.client().cachedTools() : List.of();
    }

    public List<McpToolInfo> tools(Path workspace, String server) {
        return client(workspace, server).tools(LIST_TIMEOUT);
    }

    List<McpToolInfo> tools(Path workspace, String server, Selection expected, boolean allowStart) {
        return client(workspace, server, expected, allowStart).tools(LIST_TIMEOUT);
    }

    record ReadOnlyGrant(McpClient client, McpPermissionPolicy policy, List<McpToolInfo> tools, long revision) {
    }

    McpPermissionPolicy permissionPolicy(Path workspace, String server) {
        return permissionPolicy(server, selection(workspace, server));
    }

    private McpPermissionPolicy permissionPolicy(String server, Selection selected) {
        if (userConfig == null || !current(selected) || !selected.client().running()) {
            return McpPermissionPolicy.UNTRUSTED;
        }
        try {
            JsonNode root = McpJson.readConfig(userConfig);
            McpServerConfig trusted = parseServers(root).get(server);
            if (!Objects.equals(trusted, selected.config())) {
                return McpPermissionPolicy.UNTRUSTED;
            }
            JsonNode policy = root.path("mcpServers").path(server).path("pilotPermissions");
            boolean scoped = false;
            for (JsonNode item : policy.path("workspaces")) {
                if (item.isString()) {
                    Path allowed = Path.of(item.asString());
                    scoped |=
                        allowed.isAbsolute() && McpClient.canonicalWorkspace(allowed).equals(selected.workspace());
                }
            }
            return scoped ? McpPermissionPolicy.from(policy) : McpPermissionPolicy.UNTRUSTED;
        } catch (IOException | RuntimeException e) {
            return McpPermissionPolicy.UNTRUSTED;
        }
    }

    ReadOnlyGrant readOnlyGrant(Path workspace, String server, String tool) {
        Selection selected = selection(workspace, server);
        if (!current(selected) || !selected.client().running()) {
            return null;
        }
        McpPermissionPolicy policy = permissionPolicy(server, selected);
        long revision = selected.client().catalogRevision();
        List<McpToolInfo> tools = selected.client().cachedTools();
        return tools.stream().anyMatch(info -> info.name().equals(tool) && policy.readOnly(info))
            ? new ReadOnlyGrant(selected.client(), policy, tools, revision) : null;
    }

    JsonNode call(Path workspace, String server, String tool, Map<String, Object> arguments) {
        return call(workspace, server, tool, arguments, null);
    }

    JsonNode call(Path workspace, String server, String tool, Map<String, Object> arguments, ReadOnlyGrant grant) {
        McpClient client = client(workspace, server, selection(workspace, server), false);
        if (grant != null && (!grant.equals(readOnlyGrant(workspace, server, tool)) || grant.client() != client)) {
            throw new McpException("MCP 本地只读策略、连接或工具列表已变化，请重新检查权限；未执行本次调用");
        }
        return client.call(tool, arguments, CALL_TIMEOUT);
    }

    @Override
    public synchronized void close() {
        closed = true;
        clients.values().forEach(McpClient::close);
        clients.clear();
    }

    private Map<String, McpServerConfig> load(Path file) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        JsonNode root;
        try {
            root = McpJson.readConfig(file);
            if (root == null || !root.isObject() || root.has("mcpServers") && !root.path("mcpServers").isObject()) {
                throw new McpException("根对象或 mcpServers 结构无效");
            }
        } catch (IOException | RuntimeException e) {
            result.put("(" + file.getFileName() + ")", new McpServerConfig("(" + file.getFileName() + ")", null,
                List.of(), Map.of(), "配置文件无法解析：" + e.getMessage()));
            return result;
        }
        return parseServers(root);
    }

    private Map<String, McpServerConfig> parseServers(JsonNode root) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        JsonNode servers = root.path("mcpServers");
        for (String name : servers.propertyNames()) {
            JsonNode node = servers.path(name);
            if (node.path("disabled").asBoolean(false)) {
                result.put(name, new McpServerConfig(name, null, List.of(), Map.of(), "已禁用"));
                continue;
            }
            String command = node.path("command").asString("");
            if (command.isBlank()) {
                String reason =
                    node.has("url") ? "只配置了 url（HTTP / SSE 传输），Pilot 目前仅支持 stdio（command + args）" : "缺少 command";
                result.put(name, new McpServerConfig(name, null, List.of(), Map.of(), reason));
                continue;
            }
            List<String> args = new ArrayList<>();
            for (JsonNode arg : node.path("args")) {
                args.add(arg.asString(""));
            }
            Map<String, String> env = new LinkedHashMap<>();
            JsonNode envNode = node.path("env");
            for (String key : envNode.propertyNames()) {
                env.put(key, envNode.path(key).asString(""));
            }
            result.put(name, new McpServerConfig(name, command, args, env, null));
        }
        return result;
    }

    private static String key(Path workspace, String server) {
        return workspace.toAbsolutePath().normalize() + "\u0000" + server;
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
