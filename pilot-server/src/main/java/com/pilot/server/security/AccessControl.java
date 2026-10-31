package com.pilot.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * 访问判定（纯函数，便于覆盖各种组合）。
 *
 * <p>判定顺序：本机限制 → 令牌 → 来源 → 限流。令牌比较使用常量时间比较，
 * 避免通过响应时间猜出前缀。
 *
 * <p>这里不是完整的身份系统：只有一个共享令牌，没有用户、角色和会话隔离。
 * 适合「可信局域网 + 单人使用」；要开放到公网，应在其前面加一层真正的认证代理。
 */
public final class AccessControl {

    /**
     * @param cookieToken 浏览器访问时由首次 {@code ?token=} 换来的 HttpOnly Cookie；
     *                    页面内的 fetch 与 EventSource 会自动带上它
     */
    public record Request(String method, String path, String remoteAddress, String authorizationHeader,
                          String tokenHeader, String queryToken, String cookieToken, String origin,
                          String host) {
        /** 兼容没有 Cookie 的调用方。 */
        public Request(String method, String path, String remoteAddress, String authorizationHeader,
                       String tokenHeader, String queryToken, String origin, String host) {
            this(method, path, remoteAddress, authorizationHeader, tokenHeader, queryToken, null, origin, host);
        }
    }

    /**
     * @param status HTTP 状态码；放行时为 200
     */
    public record Decision(boolean allow, int status, String reason) {
        public static Decision permit() {
            return new Decision(true, 200, "");
        }

        public static Decision reject(int status, String reason) {
            return new Decision(false, status, reason);
        }
    }

    private final SecurityProperties properties;

    public AccessControl(SecurityProperties properties) {
        this.properties = properties == null ? new SecurityProperties(null, null, null, null, null) : properties;
    }

    public Decision check(Request request) {
        if (request == null) {
            return Decision.reject(400, "无法识别的请求");
        }
        boolean loopback = isLoopback(request.remoteAddress());
        if (properties.localOnly()) {
            return loopback ? Decision.permit()
                    : Decision.reject(403, "当前只允许本机访问；如需远程访问请启用 pilot.security.mode=token 并配置令牌");
        }
        if (properties.token().isEmpty()) {
            // 配置了令牌模式却没有令牌，比放行更危险：直接拒绝并说明原因
            return Decision.reject(503, "已启用令牌模式但未配置访问令牌（pilot.security.token）");
        }
        if (!matchesToken(request)) {
            return Decision.reject(401, "缺少或错误的访问令牌");
        }
        if (!originAllowed(request, loopback)) {
            return Decision.reject(403, "来源 " + request.origin() + " 不在允许列表中");
        }
        return Decision.permit();
    }

    private boolean matchesToken(Request request) {
        String presented = firstNonBlank(request.authorizationHeader(), request.tokenHeader(),
                request.queryToken(), request.cookieToken());
        if (presented == null) {
            return false;
        }
        String value = presented.regionMatches(true, 0, "Bearer ", 0, 7)
                ? presented.substring(7).strip() : presented.strip();
        byte[] expected = properties.token().getBytes(StandardCharsets.UTF_8);
        byte[] actual = value.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 浏览器来源校验：用于替代 cookie 会话下的 CSRF 令牌。
     * 非浏览器客户端（没有 Origin）允许通过，因为它们仍受令牌约束。
     */
    private boolean originAllowed(Request request, boolean loopback) {
        String origin = request.origin();
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String normalized = origin.strip().toLowerCase(Locale.ROOT);
        if (loopback && isSameHost(normalized, request.host())) {
            return true;
        }
        if (properties.allowedOrigins().stream()
                .map(allowed -> allowed.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals)) {
            return true;
        }
        return isSameHost(normalized, request.host());
    }

    private static boolean isSameHost(String origin, String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostName = host.strip().toLowerCase(Locale.ROOT);
        return origin.equals("http://" + hostName) || origin.equals("https://" + hostName);
    }

    /** 回环地址判断：IPv4 127/8、IPv6 ::1、以及未开启 DNS 反查时的 localhost 字面量。 */
    public static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String value = address.strip();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        if (zone > 0) {
            value = value.substring(0, zone);
        }
        return value.equals("127.0.0.1") || value.startsWith("127.") || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1") || value.equalsIgnoreCase("localhost");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** 允许放行的静态资源与健康检查：它们不暴露会话数据，也不应拖慢探活。 */
    public static boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        List<String> prefixes = List.of("/api/health", "/favicon.ico");
        return prefixes.stream().anyMatch(path::startsWith);
    }
}
