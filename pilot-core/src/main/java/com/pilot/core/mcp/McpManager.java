package com.pilot.core.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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

    /** 某个工作区当前配置的全部服务器（按名字排序）。配置文件每次现读，改完立即生效。 */
    public Map<String, McpServerConfig> servers(Path workspace) {
        Map<String, McpServerConfig> merged = new java.util.TreeMap<>();
        if (userConfig != null) merged.putAll(load(userConfig));
        merged.putAll(load(workspace.resolve(".mcp.json")));
        return merged;
    }

    public boolean hasServers(Path workspace) {
        return !servers(workspace).isEmpty();
    }

    public boolean running(Path workspace, String server) {
        McpClient client = clients.get(key(workspace, server));
        return client != null && client.running();
    }

    /** 上一次启动失败的原因；从未失败或已恢复时为 {@code null}。 */
    public String failure(Path workspace, String server) {
        return failures.get(key(workspace, server));
    }

    /** 取得（必要时启动）服务器的客户端。 */
    McpClient client(Path workspace, String server) {
        McpServerConfig config = servers(workspace).get(server);
        if (config == null) {
            throw new McpException("没有名为 " + server + " 的 MCP 服务器；已配置：" + String.join(", ", servers(workspace).keySet()));
        }
        if (!config.usable()) {
            throw new McpException("MCP 服务器 " + server + " 无法使用：" + (config.unsupported() == null
                    ? "缺少 command" : config.unsupported()));
        }
        String key = key(workspace, server);
        synchronized (this) {
            McpClient existing = clients.get(key);
            if (existing != null && existing.running()) return existing;
            if (existing != null) existing.close();
            Path log = logDir == null ? null : logDir.resolve(safe(server) + ".log");
            McpClient fresh = new McpClient(server, config, workspace, log, mapper, extraPath.get());
            try {
                fresh.start(START_TIMEOUT);
                fresh.tools(LIST_TIMEOUT);
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
    }

    /** 已运行服务器声明的工具；没运行时返回空，不会为了列举而启动进程。 */
    public List<McpToolInfo> knownTools(Path workspace, String server) {
        McpClient client = clients.get(key(workspace, server));
        if (client == null || !client.running()) return List.of();
        try {
            return client.tools(LIST_TIMEOUT);
        } catch (McpException e) {
            return List.of();
        }
    }

    public List<McpToolInfo> tools(Path workspace, String server) {
        return client(workspace, server).tools(LIST_TIMEOUT);
    }

    JsonNode call(Path workspace, String server, String tool, Map<String, Object> arguments) {
        return client(workspace, server).call(tool, arguments, CALL_TIMEOUT);
    }

    @Override
    public void close() {
        clients.values().forEach(McpClient::close);
        clients.clear();
    }

    private Map<String, McpServerConfig> load(Path file) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return result;
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            result.put("(" + file.getFileName() + ")", new McpServerConfig("(" + file.getFileName() + ")", null,
                    List.of(), Map.of(), "配置文件无法解析：" + e.getMessage()));
            return result;
        }
        JsonNode servers = root.path("mcpServers");
        for (String name : servers.propertyNames()) {
            JsonNode node = servers.path(name);
            if (node.path("disabled").asBoolean(false)) continue;
            String command = node.path("command").asString("");
            if (command.isBlank()) {
                String reason = node.has("url")
                        ? "只配置了 url（HTTP / SSE 传输），Pilot 目前仅支持 stdio（command + args）"
                        : "缺少 command";
                result.put(name, new McpServerConfig(name, null, List.of(), Map.of(), reason));
                continue;
            }
            List<String> args = new ArrayList<>();
            for (JsonNode arg : node.path("args")) args.add(arg.asString(""));
            Map<String, String> env = new LinkedHashMap<>();
            JsonNode envNode = node.path("env");
            for (String key : envNode.propertyNames()) env.put(key, envNode.path(key).asString(""));
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
