package com.pilot.core.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 用户对 MCP 服务器的信任记录，按（工作区绝对路径, 服务器名）键。
 *
 * <p>MCP 规范明确 tool annotations（含 {@code readOnlyHint}）由服务器自报、不可作为安全依据，
 * 因此免审批的依据是「用户对该服务器启动命令的显式信任」而不是工具的自我声明：
 * <ul>
 *   <li><strong>configHash</strong> 钉住用户批准的那条命令（command + args + env），
 *       任一项变化即视为未信任，重新走审批；</li>
 *   <li><strong>toolHash</strong> 钉住信任时服务器声明的每个工具（name + description +
 *       inputSchema + annotations），服务器事后改写工具定义（rug-pull / tool poisoning）
 *       会被哈希漂移发现并退回审批。</li>
 * </ul>
 *
 * <p>哈希对 JSON 对象的字段顺序不敏感：规范化时递归按 key 排序，避免服务器端实现
 * 换个序列化顺序就误报漂移。
 *
 * <p>记录是纯 JSON 文件。缺失、损坏或版本不识别时按「全部未信任」处理并告警——
 * 退化方向永远是多问一次，不会放大权限。变更方法在锁内「先落盘再提交内存」；
 * 后台钉住（{@link #pinTools}）失败时只告警并返回 false，内存不更新，保持未钉住状态。
 */
public final class McpTrustStore {
    static final int FORMAT_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(McpTrustStore.class);

    /** 一个工具的钉住状态。{@code allowed=true} 表示用户对它做过「始终允许」。 */
    public record ToolPin(String hash, boolean allowed) {
    }

    /**
     * 一个服务器的信任状态：配置哈希 + 信任时间 + 已钉住的工具集。
     * {@code tools} 为空表示「已批准命令但还没观测过工具清单」（S1），首次启动后钉住。
     */
    public record ServerTrust(String configHash, Instant trustedAt, Map<String, ToolPin> tools) {
        public ServerTrust {
            tools = tools == null ? Map.of() : Map.copyOf(tools);
        }
    }

    private final ObjectMapper mapper;
    private final Path file;
    private final Map<String, Map<String, ServerTrust>> servers = new ConcurrentHashMap<>();

    /**
     * @param file 记录文件；{@code null} 表示只在内存里保存（测试、嵌入式用法）
     */
    public McpTrustStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
        load();
    }

    // ————— 查询 —————

    public Optional<ServerTrust> record(Path workspace, String server) {
        Map<String, ServerTrust> byServer = servers.get(workspaceKey(workspace));
        return byServer == null ? Optional.empty() : Optional.ofNullable(byServer.get(server));
    }

    // ————— 变更 —————

    /**
     * 记录用户对某服务器当前启动命令的信任。同一条命令（哈希相同）再次信任时保留已钉住的工具，
     * 命令变化时清空工具钉住（下次启动用新观测重新钉）。
     */
    public synchronized void trust(Path workspace, String server, String configHash) {
        String ws = workspaceKey(workspace);
        ServerTrust existing = servers.getOrDefault(ws, Map.of()).get(server);
        Map<String, ToolPin> keep =
            existing != null && existing.configHash().equals(configHash) ? existing.tools() : Map.of();
        commit(ws, server, new ServerTrust(configHash, Instant.now(), keep));
    }

    /**
     * 把服务器声明（首次信任后的观测）钉进信任记录。仅当记录存在、配置哈希一致且尚未钉住
     * （S1 → S2）时生效；已钉住的记录不覆盖——漂移必须由用户重新批准，不能被观测悄悄重写。
     *
     * @return 是否真的钉住了
     */
    public boolean pinTools(Path workspace, String server, String configHash, Map<String, ToolPin> tools) {
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        String ws = workspaceKey(workspace);
        synchronized (this) {
            ServerTrust existing = servers.getOrDefault(ws, Map.of()).get(server);
            if (existing == null || !existing.configHash().equals(configHash) || !existing.tools().isEmpty()) {
                return false;
            }
            ServerTrust updated = new ServerTrust(existing.configHash(), existing.trustedAt(), tools);
            try {
                commit(ws, server, updated);
            } catch (RuntimeException e) {
                log.warn("MCP 工具钉住记录未能落盘（{}）：{}；本次按未钉住处理", ws, e.getMessage());
                return false;
            }
            return true;
        }
    }

    /**
     * 对某个工具记「始终允许」，同时把哈希钉到当前观测值（用户批准的就是他看到的这版定义）。
     *
     * @return {@code false} 表示该服务器还没有信任记录，要先 trust
     */
    public synchronized boolean allowTool(Path workspace, String server, String tool, String hash) {
        String ws = workspaceKey(workspace);
        ServerTrust existing = servers.getOrDefault(ws, Map.of()).get(server);
        if (existing == null) {
            return false;
        }
        Map<String, ToolPin> tools = new LinkedHashMap<>(existing.tools());
        tools.put(tool, new ToolPin(hash, true));
        commit(ws, server, new ServerTrust(existing.configHash(), existing.trustedAt(), tools));
        return true;
    }

    /** 撤销信任：删除记录，之后一切操作回到逐次审批。 */
    public synchronized void revoke(Path workspace, String server) {
        String ws = workspaceKey(workspace);
        Map<String, ServerTrust> current = servers.get(ws);
        if (current == null || !current.containsKey(server)) {
            return;
        }
        Map<String, ServerTrust> updated = new LinkedHashMap<>(current);
        updated.remove(server);
        save(snapshotWith(ws, updated));
        if (updated.isEmpty()) {
            servers.remove(ws);
        } else {
            servers.put(ws, Map.copyOf(updated));
        }
    }

    // ————— 哈希 —————

    /** 服务器配置指纹：stdio 钉住 command+args+env（保持 v1 兼容）；远程钉住 transport+url+headers。 */
    public static String hash(McpServerConfig config) {
        if (config.isRemote()) {
            StringBuilder canonical = new StringBuilder("mcp-remote-config-v1\n");
            canonical.append("transport=").append(escape(String.valueOf(config.transport()))).append('\n');
            canonical.append("url=").append(escape(String.valueOf(config.url()))).append('\n');
            Map<String, String> headers = new TreeMap<>();
            if (config.headers() != null) {
                headers.putAll(config.headers());
            }
            canonical.append("headers=").append(headers.size()).append('\n');
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                canonical.append("header.").append(escape(entry.getKey())).append('=')
                    .append(escape(entry.getValue())).append('\n');
            }
            return sha256(canonical.toString());
        }
        StringBuilder canonical = new StringBuilder("mcp-config-v1\n");
        canonical.append("command=").append(escape(String.valueOf(config.command()))).append('\n');
        canonical.append("args=").append(config.args().size()).append('\n');
        for (String arg : config.args()) {
            canonical.append("arg=").append(escape(arg)).append('\n');
        }
        Map<String, String> env = new TreeMap<>();
        if (config.env() != null) {
            env.putAll(config.env());
        }
        canonical.append("env=").append(env.size()).append('\n');
        for (Map.Entry<String, String> entry : env.entrySet()) {
            canonical.append("env.").append(escape(entry.getKey())).append('=').append(escape(entry.getValue()))
                .append('\n');
        }
        return sha256(canonical.toString());
    }

    /**
     * 工具声明的指纹：name + description + inputSchema + annotations。
     * annotations 一并纳入——信任后再翻转变量 {@code readOnlyHint} 同样算漂移。
     */
    public static String hash(McpToolInfo tool) {
        StringBuilder canonical = new StringBuilder("mcp-tool-v1\n");
        canonical.append("name=").append(escape(tool.name())).append('\n');
        canonical.append("description=").append(escape(tool.description())).append('\n');
        canonical.append("schema=");
        canonical(tool.inputSchema(), canonical);
        canonical.append("\nannotations=");
        canonical(tool.annotations(), canonical);
        return sha256(canonical.toString());
    }

    // ————— 内部 —————

    /** 先落盘再提交内存；落盘失败抛出，调用方对用户可见的动作（trust/allowTool/revoke）据此报错。 */
    private void commit(String ws, String server, ServerTrust updated) {
        Map<String, ServerTrust> current = servers.getOrDefault(ws, Map.of());
        Map<String, ServerTrust> next = new LinkedHashMap<>(current);
        next.put(server, updated);
        save(snapshotWith(ws, next));
        servers.put(ws, Map.copyOf(next));
    }

    private Map<String, Map<String, ServerTrust>> snapshotWith(String ws, Map<String, ServerTrust> updated) {
        Map<String, Map<String, ServerTrust>> next = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ServerTrust>> entry : servers.entrySet()) {
            if (!entry.getKey().equals(ws)) {
                next.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
        }
        if (updated != null && !updated.isEmpty()) {
            next.put(ws, Map.copyOf(updated));
        }
        return next;
    }

    private void save(Map<String, Map<String, ServerTrust>> snapshot) {
        if (file == null) {
            return;
        }
        Map<String, Object> serversJson = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ServerTrust>> wsEntry : snapshot.entrySet()) {
            Map<String, Object> byServer = new LinkedHashMap<>();
            for (Map.Entry<String, ServerTrust> trust : wsEntry.getValue().entrySet()) {
                byServer.put(trust.getKey(), serverJson(trust.getValue()));
            }
            serversJson.put(wsEntry.getKey(), byServer);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", FORMAT_VERSION);
        root.put("servers", serversJson);
        try {
            Path absolute = file.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Path tmp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(root));
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法写入 MCP 信任记录 " + file + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> serverJson(ServerTrust trust) {
        Map<String, Object> tools = new LinkedHashMap<>();
        for (Map.Entry<String, ToolPin> pin : trust.tools().entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("hash", pin.getValue().hash());
            entry.put("allowed", pin.getValue().allowed());
            tools.put(pin.getKey(), entry);
        }
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("configHash", trust.configHash());
        server.put("trustedAt", trust.trustedAt().toString());
        server.put("tools", tools);
        return server;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            if (Files.size(file) > McpJson.MAX_CONFIG_BYTES) {
                log.warn("MCP 信任记录 {} 超过大小上限，所有服务器按未信任处理", file);
                return;
            }
        } catch (IOException e) {
            log.warn("MCP 信任记录 {} 无法读取大小，按未信任处理: {}", file, e.getMessage());
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            log.warn("MCP 信任记录 {} 无法读取，所有服务器按未信任处理: {}", file, e.getMessage());
            return;
        }
        if (root.path("version").asInt(-1) != FORMAT_VERSION) {
            log.warn("MCP 信任记录 {} 版本不识别（期望 {}），所有服务器按未信任处理", file, FORMAT_VERSION);
            return;
        }
        try {
            JsonNode serversNode = root.path("servers");
            for (String ws : serversNode.propertyNames()) {
                JsonNode wsNode = serversNode.path(ws);
                Map<String, ServerTrust> byServer = new LinkedHashMap<>();
                for (String name : wsNode.propertyNames()) {
                    byServer.put(name, trustJson(wsNode.path(name)));
                }
                servers.put(ws, Map.copyOf(byServer));
            }
        } catch (RuntimeException e) {
            log.warn("MCP 信任记录 {} 结构异常，忽略损坏内容: {}", file, e.getMessage());
        }
    }

    private static ServerTrust trustJson(JsonNode node) {
        Map<String, ToolPin> tools = new LinkedHashMap<>();
        JsonNode toolsNode = node.path("tools");
        for (String tool : toolsNode.propertyNames()) {
            JsonNode pin = toolsNode.path(tool);
            tools.put(tool, new ToolPin(pin.path("hash").asString(""), pin.path("allowed").asBoolean(false)));
        }
        Instant trustedAt;
        try {
            trustedAt = Instant.parse(node.path("trustedAt").asString(""));
        } catch (RuntimeException e) {
            trustedAt = Instant.EPOCH;
        }
        return new ServerTrust(node.path("configHash").asString(""), trustedAt, tools);
    }

    private static String workspaceKey(Path workspace) {
        return workspace.toAbsolutePath().normalize().toString();
    }

    /** JSON 值的规范化：对象按 key 排序，数组保序，missing/null 统一为 null，标量取 JSON 文本。 */
    private static void canonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            Collections.sort(names);
            out.append('{');
            for (String name : names) {
                out.append(escape(name)).append(':');
                canonical(node.path(name), out);
                out.append(',');
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (JsonNode item : node) {
                canonical(item, out);
                out.append(',');
            }
            out.append(']');
            return;
        }
        out.append(node.toString());
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        appendEscaped(value, out);
        return out.toString();
    }

    private static void appendEscaped(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder("sha256:")
                .append(HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要不可用", e);
        }
    }
}
