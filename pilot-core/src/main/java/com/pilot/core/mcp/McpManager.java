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

import com.pilot.core.util.FileTrees;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP 服务器的配置与生命周期。
 *
 * <p>配置来自工作区的 {@code .mcp.json}（与 Claude Code 同一格式：{@code {"mcpServers": {"名字": {"command": …}}}}）
 * 以及可选的用户级文件，同名时工作区优先。服务器按「工作区 + 名字」懒启动：
 * 模型第一次真正用到时才拉起进程，之后复用，服务关闭时统一回收。
 *
 * <p>启动一个服务器等于执行配置里的命令，因此调用方（工具层）必须把「启动」当作需要审批的动作；
 * 信任记录与决策矩阵见 {@link McpTrustStore} 与 {@link McpTrustPolicy}：启动成功后把服务器声明的
 * 工具钉进记录，之后的调用按「已信任 + 哈希未漂移」放行，检测到定义漂移则退回审批。
 */
public final class McpManager implements AutoCloseable {
    static final Duration START_TIMEOUT = Duration.ofSeconds(40);
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper mapper;
    private final Path userConfig;
    private final Path logDir;
    private final Supplier<String> extraPath;
    private final McpTrustStore trustStore;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Object> clientLocks = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final Map<String, String> notes = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param userConfig 用户级配置文件，可为 {@code null}
     * @param logDir     服务器 stderr 的日志目录，可为 {@code null}（丢弃）
     * @param extraPath  追加到子进程 PATH 前面的目录（内置 Node 等）；每次启动服务器时取值，
     *                   因为内置运行时在 Bean 创建之后才准备好
     */
    public McpManager(ObjectMapper mapper, Path userConfig, Path logDir, Supplier<String> extraPath) {
        this(mapper, userConfig, logDir, extraPath, null);
    }

    /**
     * @param trustStore 信任记录；{@code null} 表示没有信任能力，所有服务器按未信任处理（每次审批）
     */
    public McpManager(ObjectMapper mapper, Path userConfig, Path logDir, Supplier<String> extraPath,
        McpTrustStore trustStore) {
        this.mapper = mapper;
        this.userConfig = userConfig;
        this.logDir = logDir;
        this.extraPath = extraPath == null ? () -> "" : extraPath;
        this.trustStore = trustStore;
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
        String extra = Objects.toString(extraPath.get(), "");
        Path canonicalWorkspace = McpClient.canonicalWorkspace(workspace);
        String clientKey = key(workspace, server);
        McpClient cached = clients.get(clientKey);
        if (cached != null) {
            if (config == null || !config.usable()) {
                discardClient(clientKey, cached);
            } else if (!cached.matches(config, extra) || !cached.matchesWorkspace(canonicalWorkspace)) {
                // 配置变更后立即关闭旧子进程/远端传输防止泄漏，同时保留已停用句柄供状态页展示“配置已变更”，
                // 直到重新发现工具或热替换配置时移除并启动新连接。
                cached.close();
            }
        }
        return new Selection(config, extra, clients.get(clientKey), canonicalWorkspace);
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

    /** 信任记录；没有信任能力时为 {@code null}。 */
    public McpTrustStore trustStore() {
        return trustStore;
    }

    /**
     * 用户级配置的整体树（只读快照）：没有文件或解析失败时返回空对象。
     * 写入口必须先调用 {@link #userConfigReadable()} 或 {@link #updateUserConfig}，避免把损坏文件覆盖掉。
     */
    public JsonNode readUserConfig() {
        if (userConfig == null || !Files.isRegularFile(userConfig)) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode root = McpJson.readConfig(userConfig);
            return root.isObject() ? root : mapper.createObjectNode();
        } catch (IOException | RuntimeException e) {
            return mapper.createObjectNode();
        }
    }

    /** 用户级配置存在、可解析且是对象；损坏时写入口应拒绝写入而不是覆盖。 */
    public boolean userConfigReadable() {
        if (userConfig == null || !Files.isRegularFile(userConfig)) {
            return true;
        }
        try {
            return McpJson.readConfig(userConfig).isObject();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 在管理器内串行地读—改—写用户级配置，避免并发 add/import 互相覆盖。
     * 配置损坏时拒绝写入，返回的异常由调用方转成可见错误。
     */
    public synchronized <T> T updateUserConfig(java.util.function.Function<ObjectNode, T> mutation) throws IOException {
        if (!userConfigReadable()) {
            throw new IOException("用户级 MCP 配置无法解析，请先修复或备份后重试；本次未写入");
        }
        ObjectNode root = (ObjectNode)readUserConfig();
        T result = mutation.apply(root);
        saveUserConfig(root);
        return result;
    }

    /** 用户级配置里是否有某个服务器（同名时工作区会覆盖它）。 */
    public boolean definedInUserConfig(String server) {
        return readUserConfig().path("mcpServers").has(server);
    }

    /** 工作区 .mcp.json 里是否有某个服务器；同名时它才是生效来源。 */
    public boolean definedInWorkspaceConfig(Path workspace, String server) {
        return load(workspace.resolve(".mcp.json")).containsKey(server);
    }

    /** 写回用户级配置：先落唯一临时文件再原子替换；写前检查大小，避免写出读不回来的文件。 */
    public synchronized void saveUserConfig(JsonNode root) throws IOException {
        Map<String, McpServerConfig> before = userConfig == null ? Map.of() : load(userConfig);
        Objects.requireNonNull(root, "root must not be null");
        if (userConfig == null) {
            throw new IOException("没有配置用户级 mcp.json 的路径");
        }
        Path absolute = userConfig.toAbsolutePath();
        byte[] bytes = mapper.writeValueAsBytes(root);
        if (bytes.length > McpJson.MAX_CONFIG_BYTES) {
            throw new McpException("MCP 用户级配置超过 1 MiB 上限，请精简后重试；本次未写入");
        }
        FileTrees.writeAtomic(absolute, bytes);
        retireChangedServers(before, load(userConfig));
    }

    private void retireChangedServers(Map<String, McpServerConfig> before, Map<String, McpServerConfig> after) {
        before.forEach((server, config) -> {
            if (!Objects.equals(config, after.get(server))) {
                clients.forEach((key, client) -> {
                    if (key.endsWith("\u0000" + server)) {
                        discardClient(key, client);
                    }
                });
            }
        });
    }

    /** 上次启动时检测到的工具定义漂移说明；无漂移或未信任时为 {@code null}。 */
    public String trustNote(Path workspace, String server) {
        return notes.get(key(workspace, server));
    }

    /**
     * 信任状态四态（供设置页签与能力面板展示，语义对应 McpTrustPolicy）：
     * UNTRUSTED 无记录；TRUSTED_UNPINNED 已批准当前命令但工具未钉住（下次启动钉）；
     * TRUSTED 已批准且已钉住工具定义；STALE 有记录但当前命令与批准时不一致。
     */
    public String trustState(Path workspace, String server) {
        if (trustStore == null) {
            return "UNTRUSTED";
        }
        McpTrustStore.ServerTrust record = trustStore.record(workspace, server).orElse(null);
        if (record == null) {
            return "UNTRUSTED";
        }
        McpServerConfig config = servers(workspace).get(server);
        if (config == null || !config.usable()) {
            return "UNTRUSTED";
        }
        return record.configHash().equals(McpTrustStore.hash(config))
            ? (record.tools().isEmpty() ? "TRUSTED_UNPINNED" : "TRUSTED") : "STALE";
    }

    /**
     * 信任动作发出后：若服务器已在运行，立即把当前观测的工具清单钉进信任记录（S1 → S2）。
     * 服务器未运行、配置哈希不匹配或已钉住时不动。
     *
     * @return 本次调用后服务器是否处于「已钉住」状态
     */
    public boolean pinIfRunning(Path workspace, String server) {
        if (trustStore == null) {
            return false;
        }
        Selection selected = selection(workspace, server);
        McpServerConfig config = selected.config();
        if (config == null || !config.usable()) {
            return false;
        }
        McpTrustStore.ServerTrust record = trustStore.record(workspace, server).orElse(null);
        if (record == null) {
            return false;
        }
        String currentHash = McpTrustStore.hash(config);
        if (!record.configHash().equals(currentHash)) {
            return false;
        }
        if (!record.tools().isEmpty()) {
            return true;
        }
        if (!current(selected) || !selected.client().running()) {
            return false;
        }
        List<McpToolInfo> observed = selected.client().cachedTools();
        if (observed.isEmpty()) {
            return false;
        }
        trustStore.pinTools(workspace, server, currentHash, pins(observed));
        return trustStore.record(workspace, server).map(saved -> !saved.tools().isEmpty()).orElse(false);
    }

    /** 只有工具发现入口可以启动进程；实际调用不隐式启动或重新连接。 */
    McpClient client(Path workspace, String server) {
        return client(workspace, server, selection(workspace, server), true);
    }

    private McpClient client(Path workspace, String server, Selection expected, boolean allowStart) {
        String key = key(workspace, server);
        synchronized (clientLocks.computeIfAbsent(key, ignored -> new Object())) {
            return clientLocked(workspace, server, expected, allowStart, key);
        }
    }

    private McpClient clientLocked(Path workspace, String server, Selection expected, boolean allowStart, String key) {
        if (closed) {
            throw new McpException("MCP 管理器已关闭");
        }
        Selection selected = selection(workspace, server);
        validateExpected(selected, expected);
        McpServerConfig config = requireUsable(server, selected.config());
        McpClient existing = selected.client();
        if (existing != null && !current(selected)) {
            if (!allowStart) {
                existing.close();
                throw new McpException("MCP 服务器 " + server + " 配置已变更，旧连接已停用；请先用 mcp_tools 重新发现工具后再按新配置启动");
            }
            discardClient(key, existing);
            existing = null;
        }
        validateRunningPermission(server, existing, expected, allowStart);
        if (existing != null && existing.running()) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        return startClient(workspace, server, key, selected, config);
    }

    private void validateExpected(Selection selected, Selection expected) {
        if (!Objects.equals(selected.config(), expected.config()) || !selected.extraPath().equals(expected.extraPath())
            || !selected.workspace().equals(expected.workspace())) {
            throw new McpException("MCP 配置在权限检查后已变化，请重新发现并确认启动配置");
        }
    }

    private McpServerConfig requireUsable(String server, McpServerConfig config) {
        if (config == null) {
            throw new McpException("没有名为 " + server + " 的 MCP 服务器；请先用 mcp_tools 查看配置");
        }
        if (!config.usable()) {
            throw new McpException(
                "MCP 服务器 " + server + " 无法使用：" + (config.unsupported() == null ? "缺少 command" : config.unsupported()));
        }
        return config;
    }

    private void validateRunningPermission(String server, McpClient existing, Selection expected, boolean allowStart) {
        if (!allowStart && (existing == null || existing != expected.client() || !existing.running())) {
            throw new McpException("MCP 连接状态已变化或尚未启动；请先用 mcp_tools 重新发现并按权限确认，未执行本次调用");
        }
    }

    private McpClient startClient(Path workspace, String server, String key, Selection selected,
        McpServerConfig config) {
        Path log = logDir == null ? null : logDir.resolve(safe(server) + ".log");
        McpClient fresh = new McpClient(server, config, workspace, log, mapper, selected.extraPath());
        try {
            fresh.start(START_TIMEOUT);
            List<McpToolInfo> declared = fresh.tools(LIST_TIMEOUT);
            Selection after = selection(workspace, server);
            validateExpected(after, new Selection(config, selected.extraPath(), null, selected.workspace()));
            noteTrust(workspace, server, config, declared);
        } catch (McpException e) {
            fresh.close();
            clients.remove(key);
            failures.put(key, e.getMessage());
            throw e;
        }
        failures.remove(key);
        clients.put(key, fresh);
        if (closed) {
            discardClient(key, fresh);
            throw new McpException("MCP 管理器已关闭");
        }
        return fresh;
    }

    private void discardClient(String key, McpClient expected) {
        if (clients.remove(key, expected)) {
            expected.close();
        }
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
            if (!policy.path("workspaces").isArray()) {
                return McpPermissionPolicy.UNTRUSTED;
            }
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
        // 先取句柄再关闭：terminate 会把 process 置空
        List<ProcessHandle> handles = new ArrayList<>();
        for (McpClient client : clients.values()) {
            ProcessHandle handle = client.handle();
            if (handle != null) {
                handles.add(handle);
            }
        }
        closed = true;
        clients.values().forEach(McpClient::close);
        clients.clear();
        notes.clear();
        // 等待子进程真正退出：否则 Windows 上进程仍占着工作区目录，删除/重建会失败。
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        for (ProcessHandle handle : handles) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                break;
            }
            try {
                handle.onExit().get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // 已退出或超时都不阻塞关闭流程
            }
        }
    }

    /**
     * 启动成功后的信任维护：S1（已批准命令但未观测过工具）钉住本次声明；S2 与钉住值比对，
     * 已钉住工具的哈希漂移记录为说明——不改动记录，调用时自然退回审批。
     */
    private void noteTrust(Path workspace, String server, McpServerConfig config, List<McpToolInfo> declared) {
        notes.remove(key(workspace, server));
        if (trustStore == null || !config.usable()) {
            return;
        }
        McpTrustStore.ServerTrust record = trustStore.record(workspace, server).orElse(null);
        if (record == null || !record.configHash().equals(McpTrustStore.hash(config))) {
            return;
        }
        if (record.tools().isEmpty()) {
            trustStore.pinTools(workspace, server, record.configHash(), pins(declared));
            return;
        }
        List<String> drifted = new ArrayList<>();
        for (Map.Entry<String, McpTrustStore.ToolPin> pinned : record.tools().entrySet()) {
            McpToolInfo current = null;
            for (McpToolInfo info : declared) {
                if (info.name().equals(pinned.getKey())) {
                    current = info;
                    break;
                }
            }
            if (current != null && !McpTrustStore.hash(current).equals(pinned.getValue().hash())) {
                drifted.add(pinned.getKey());
            }
        }
        if (!drifted.isEmpty()) {
            notes.put(key(workspace, server), "工具定义与受信任版本不一致：" + String.join("、", drifted));
        }
    }

    private static Map<String, McpTrustStore.ToolPin> pins(List<McpToolInfo> tools) {
        Map<String, McpTrustStore.ToolPin> pins = new LinkedHashMap<>();
        for (McpToolInfo info : tools) {
            pins.put(info.name(), new McpTrustStore.ToolPin(McpTrustStore.hash(info), false));
        }
        return pins;
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
            String command = node.path("command").asString("").strip();
            String url = node.path("url").asString("").strip();
            String type = node.path("type").asString("").strip();
            if (!url.isEmpty() && command.isEmpty()) {
                String lowerType = type.toLowerCase(java.util.Locale.ROOT);
                if (!lowerType.isEmpty() && !"http".equals(lowerType) && !"sse".equals(lowerType)
                    && !"streamable-http".equals(lowerType) && !"streamable_http".equals(lowerType)
                    && !"streamablehttp".equals(lowerType)) {
                    result.put(name, McpServerConfig.remote(name, McpServerConfig.TRANSPORT_HTTP, url, Map.of(),
                        "不支持的传输类型：" + type + "（支持 stdio / http / sse）"));
                    continue;
                }
                String urlError = McpServerConfig.validateRemoteUrl(url);
                Map<String, String> headers = new LinkedHashMap<>();
                JsonNode headersNode = node.path("headers");
                if (headersNode.isObject()) {
                    for (String key : headersNode.propertyNames()) {
                        headers.put(key, headersNode.path(key).asString(""));
                    }
                }
                String normalizedTransport =
                    "sse".equals(lowerType) ? McpServerConfig.TRANSPORT_SSE : McpServerConfig.TRANSPORT_HTTP;
                result.put(name, McpServerConfig.remote(name, normalizedTransport, url, headers, urlError));
                continue;
            }
            if (command.isEmpty()) {
                result.put(name, new McpServerConfig(name, null, List.of(), Map.of(), "缺少 command 或 url"));
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
