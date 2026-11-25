package com.pilot.core.mcp;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个 MCP 服务器的配置（支持本地 stdio 与远程 Streamable HTTP / SSE 传输）。
 *
 * @param transport   {@code "stdio"}、{@code "http"}（Streamable HTTP）或 {@code "sse"}
 * @param url         远程服务器地址（仅 http / sse 使用）
 * @param headers     远程请求自定义头（仅 http / sse 使用；toString 中自动脱敏）
 * @param unsupported 非空表示配置存在但当前无法使用，原因会原样展示给模型与用户
 */
public record McpServerConfig(String name, String transport, String command, List<String> args, Map<String, String> env,
    String url, Map<String, String> headers, String unsupported) {
    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";
    public static final String TRANSPORT_SSE = "sse";

    public McpServerConfig {
        transport = normalizeTransport(transport);
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        url = url == null ? "" : url.strip();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 向后兼容的 stdio 构造器。 */
    public McpServerConfig(String name, String command, List<String> args, Map<String, String> env,
        String unsupported) {
        this(name, TRANSPORT_STDIO, command, args, env, "", Map.of(), unsupported);
    }

    public static McpServerConfig remote(String name, String transport, String url, Map<String, String> headers,
        String unsupported) {
        String normalized = TRANSPORT_SSE.equals(normalizeTransport(transport)) ? TRANSPORT_SSE : TRANSPORT_HTTP;
        return new McpServerConfig(name, normalized, null, List.of(), Map.of(), url, headers, unsupported);
    }

    public boolean isRemote() {
        return TRANSPORT_HTTP.equals(transport) || TRANSPORT_SSE.equals(transport);
    }

    public boolean usable() {
        if (unsupported != null) {
            return false;
        }
        return isRemote() ? !url.isBlank() : (command != null && !command.isBlank());
    }

    /** 校验远程 MCP URL：只允许 http/https，拒绝内嵌凭据 userinfo。 */
    public static String validateRemoteUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "缺少远程 MCP 服务器 url";
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException e) {
            return "远程 MCP url 不是合法地址：" + rawUrl;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "远程 MCP url 只支持 http/https 协议：" + rawUrl;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "远程 MCP url 缺少主机名：" + rawUrl;
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isBlank()) {
            return "远程 MCP url 不允许内嵌账号密码，请改用 headers 传递认证信息";
        }
        return null;
    }

    public static String normalizeTransport(String raw) {
        if (raw == null || raw.isBlank()) {
            return TRANSPORT_STDIO;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "http", "streamable-http", "streamable_http", "streamablehttp" -> TRANSPORT_HTTP;
            case "sse" -> TRANSPORT_SSE;
            default -> TRANSPORT_STDIO;
        };
    }

    /** 覆盖默认 toString，避免日志或异常意外打印 env / headers 中的敏感令牌。 */
    @Override
    public String toString() {
        if (isRemote()) {
            return "McpServerConfig[name=" + name + ", transport=" + transport + ", url=" + url + ", headers="
                + headers.keySet() + ", unsupported=" + unsupported + "]";
        }
        return "McpServerConfig[name=" + name + ", transport=" + transport + ", command=" + command + ", args=" + args
            + ", env=" + env.keySet() + ", unsupported=" + unsupported + "]";
    }
}
