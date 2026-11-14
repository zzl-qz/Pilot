package com.pilot.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * 远程访问边界。
 *
 * <p>默认只允许本机回环访问；开启 {@code pilot.security.mode=token} 后允许远程，但必须先通过令牌、
 * 来源校验与限流。SSE 的 {@code EventSource} 不能设置请求头，因此令牌也接受 {@code ?token=} 形式，
 * 这也是为什么必须同时校验来源并限制速率。
 *
 * <p>API 拒绝返回 JSON；浏览器首次打开首页时引导至无数据的令牌登录页。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class RemoteAccessFilter extends OncePerRequestFilter {
    /** 首次用 {@code ?token=} 通过后写入的 HttpOnly Cookie。 */
    static final String COOKIE_NAME = "PILOT_ACCESS";

    private final SecurityProperties properties;
    private final AccessControl control;
    private final AccessAudit audit;
    private final SlidingWindowRateLimiter limiter;

    public RemoteAccessFilter(SecurityProperties properties, AccessAudit audit) {
        this.properties = properties;
        this.control = new AccessControl(properties);
        this.audit = audit;
        this.limiter = properties.tokenMode() && properties.rateLimitPerMinute() > 0
            ? new SlidingWindowRateLimiter(properties.rateLimitPerMinute(), SlidingWindowRateLimiter.DEFAULT_WINDOW)
            : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String remote = request.getRemoteAddr();
        String path = request.getRequestURI();
        String cookieToken = cookie(request, COOKIE_NAME);
        String host = request.getHeader("Host");
        // HTTP/1.0 与 MockMvc 可不携带 Host；Servlet 的本机服务器名称提供兼容回退。
        if (host == null) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        AccessControl.Request candidate = new AccessControl.Request(request.getMethod(), path, remote,
            request.getHeader("Authorization"), request.getHeader("X-Pilot-Token"), request.getParameter("token"),
            cookieToken, request.getHeader("Origin"), host, request.getScheme(), request.getHeader("Sec-Fetch-Site"),
            request.getHeader("Referer"));
        if (java.util.Collections.list(request.getHeaders("Origin")).size() > 1
            || java.util.Collections.list(request.getHeaders("Host")).size() > 1) {
            deny(response, remote, request, AccessControl.Decision.reject(403, "拒绝重复的来源或 Host 请求头"), candidate);
            return;
        }
        AccessControl.Decision boundary = control.localBoundary(candidate);
        if (!boundary.allow()) {
            deny(response, remote, request, boundary, candidate);
            return;
        }
        response.addHeader("Vary", "Origin, Sec-Fetch-Site, Referer, Host");
        if ("GET".equals(request.getMethod()) && "/connect".equals(path)) {
            connectPage(response);
            return;
        }
        if (AccessControl.isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        AccessControl.Decision decision = control.check(candidate);
        // 失败的登录也计入限流，避免只限制通过认证后的请求。
        if (limiter != null && !limiter.allow(remote)) {
            AccessControl.Decision limited = AccessControl.Decision.reject(429, "请求过于频繁，请稍后再试");
            deny(response, remote, request, limited, candidate);
            return;
        }
        if (!decision.allow()) {
            if (decision.status() == 401 && "GET".equals(request.getMethod())
                && ("/".equals(path) || "/index.html".equals(path)) && request.getHeader("Accept") != null
                && request.getHeader("Accept").contains("text/html")) {
                response.setHeader("Cache-Control", "no-store");
                response.sendRedirect("/connect");
                return;
            }
            // 拒绝理由需要给用户看，同时写审计
            deny(response, remote, request, decision, candidate);
            return;
        }
        if ("POST".equals(request.getMethod()) && "/api/access/login".equals(path)) {
            if (properties.tokenMode()) {
                accessCookie(response, request, properties.token(), 86400);
            }
            response.setHeader("Cache-Control", "no-store");
            response.setStatus(204);
            return;
        }
        if ("POST".equals(request.getMethod()) && "/api/access/logout".equals(path)) {
            accessCookie(response, request, "", 0);
            response.setStatus(204);
            return;
        }
        // 浏览器只会在首次访问时带 ?token=；换成 HttpOnly Cookie，页面内的 fetch/EventSource 才能继续带上
        if (properties.tokenMode() && request.getParameter("token") != null) {
            accessCookie(response, request, properties.token(), 86400);
        }
        if (properties.tokenMode() && request.getParameter("token") != null && "GET".equals(request.getMethod())
            && ("/".equals(path) || "/index.html".equals(path))) {
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect("/");
            return;
        }
        chain.doFilter(request, response);
    }

    private void accessCookie(HttpServletResponse response, HttpServletRequest request, String token, int seconds) {
        response.addHeader("Set-Cookie", COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
            + seconds + (request.isSecure() ? "; Secure" : ""));
    }

    private void connectPage(HttpServletResponse response) throws IOException {
        String nonce = UUID.randomUUID().toString();
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy",
            "default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce
                + "'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        try (var input = RemoteAccessFilter.class.getResourceAsStream("/remote-connect.html")) {
            if (input == null) {
                response.sendError(503, "登录页面不可用");
                return;
            }
            response.getWriter()
                .write(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("__NONCE__", nonce));
        }
    }

    private static String cookie(HttpServletRequest request, String name) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie item : cookies) {
            if (name.equals(item.getName()) && item.getValue() != null && !item.getValue().isBlank()) {
                return item.getValue();
            }
        }
        return null;
    }

    private void deny(HttpServletResponse response, String remote, HttpServletRequest request,
        AccessControl.Decision decision, AccessControl.Request candidate) throws IOException {
        boolean tokenPresent = candidate.authorizationHeader() != null || candidate.tokenHeader() != null
            || candidate.queryToken() != null;
        audit.record(remote, request.getMethod(), request.getRequestURI(), decision.status(), decision.reason(),
            tokenPresent);
        log.warn("拒绝访问 {} {}（来自 {}）：{}", request.getMethod(), request.getRequestURI(), remote, decision.reason());
        response.setStatus(decision.status());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
            .write("{\"error\":\"" + escape(decision.reason()) + "\",\"status\":" + decision.status() + "}");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
