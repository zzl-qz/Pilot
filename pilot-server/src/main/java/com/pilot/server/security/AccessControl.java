package com.pilot.server.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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
        String tokenHeader, String queryToken, String cookieToken, String origin, String host, String scheme,
        String fetchSite, String referer) {
        public Request(String method, String path, String remote, String authorization, String token, String query,
            String cookie, String origin, String host) {
            this(method, path, remote, authorization, token, query, cookie, origin, host, null, null, null);
        }

        /** 兼容没有 Cookie 的调用方。 */
        public Request(String method, String path, String remoteAddress, String authorizationHeader, String tokenHeader,
            String queryToken, String origin, String host) {
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
        if (properties.localOnly()) {
            return localBoundary(request);
        }
        if (properties.token().isEmpty()) {
            // 配置了令牌模式却没有令牌，比放行更危险：直接拒绝并说明原因
            return Decision.reject(503, "已启用令牌模式但未配置访问令牌（pilot.security.token）");
        }
        if (!matchesToken(request)) {
            return Decision.reject(401, "缺少或错误的访问令牌");
        }
        if (!originAllowed(request)) {
            return Decision.reject(403, "来源 " + request.origin() + " 不在允许列表中");
        }
        return Decision.permit();
    }

    private boolean matchesToken(Request request) {
        String presented = firstNonBlank(request.authorizationHeader(), request.tokenHeader(), request.queryToken(),
            request.cookieToken());
        if (presented == null) {
            return false;
        }
        String value =
            presented.regionMatches(true, 0, "Bearer ", 0, 7) ? presented.substring(7).strip() : presented.strip();
        byte[] expected = properties.token().getBytes(StandardCharsets.UTF_8);
        byte[] actual = value.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /** 本机边界也用于公开页面与健康检查；不能通过这些入口绕过 Host/来源检查。 */
    public Decision localBoundary(Request request) {
        if (!properties.localOnly()) {
            return Decision.permit();
        }
        if (!isLoopback(request.remoteAddress())) {
            return Decision.reject(403, "当前只允许本机访问；如需远程访问请启用 pilot.security.mode=token 并配置令牌");
        }
        URI host = authority(request.host());
        if (host == null || !isLoopback(host.getHost()) && properties.allowedHosts().stream()
            .map(AccessControl::authority).noneMatch(allowed -> sameAuthority(host, allowed))) {
            return Decision.reject(403, "Host 不在本机允许列表中");
        }
        if (!originAllowed(request)) {
            return Decision.reject(403, "来源不在允许列表中");
        }
        // 没有 Origin 的浏览器 GET 仍可能由外站图片/跳转触发；CLI 不发送此浏览器标记。
        if (request.origin() == null || request.origin().isBlank()) {
            if (request.fetchSite() != null && !request.fetchSite().isBlank()
                && !List.of("same-origin", "none").contains(request.fetchSite().toLowerCase(Locale.ROOT))) {
                return Decision.reject(403, "拒绝跨站浏览器请求");
            }
            if (request.referer() != null && !request.referer().isBlank()) {
                URI referer = url(request.referer(), false);
                if (referer == null || !originMatches(request, originOf(referer))) {
                    return Decision.reject(403, "Referer 来源不在允许列表中");
                }
            }
        }
        return Decision.permit();
    }

    private boolean originAllowed(Request request) {
        return request.origin() == null || request.origin().isBlank() || originMatches(request, request.origin());
    }

    private boolean originMatches(Request request, String value) {
        URI origin = url(value, true);
        if (origin == null) {
            return false;
        }
        if (properties.allowedOrigins().stream().map(allowed -> url(allowed, true))
            .anyMatch(allowed -> sameOrigin(origin, allowed))) {
            return true;
        }
        URI host = authority(request.host());
        URI target = host == null ? null
            : url((request.scheme() == null ? origin.getScheme() : request.scheme()) + "://" + request.host(), true);
        return sameOrigin(origin, target);
    }

    private static URI authority(String host) {
        if (host == null || host.isBlank() || !host.equals(host.strip())) {
            return null;
        }
        return url("http://" + host, true);
    }

    private static URI url(String value, boolean originOnly) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || originOnly
                    && (uri.getRawQuery() != null || uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String originOf(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static boolean sameOrigin(URI a, URI b) {
        return sameAuthority(a, b) && a.getScheme().equalsIgnoreCase(b.getScheme());
    }

    private static boolean sameAuthority(URI a, URI b) {
        return a != null && b != null && a.getHost().equalsIgnoreCase(b.getHost())
            && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
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
        if (value.equals("::1") || value.equals("0:0:0:0:0:0:0:1") || value.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!value.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) {
            return false;
        }
        return Arrays.stream(value.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);

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
