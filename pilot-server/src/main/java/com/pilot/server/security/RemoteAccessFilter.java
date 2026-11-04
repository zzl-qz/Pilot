package com.pilot.server.security;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 远程访问边界。
 *
 * <p>默认只允许本机回环访问；开启 {@code pilot.security.mode=token} 后允许远程，但必须先通过令牌、
 * 来源校验与限流。SSE 的 {@code EventSource} 不能设置请求头，因此令牌也接受 {@code ?token=} 形式，
 * 这也是为什么必须同时校验来源并限制速率。
 *
 * <p>拒绝一律返回 JSON，包含可执行的原因，避免「明明配了却访问不了」无从排查。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RemoteAccessFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RemoteAccessFilter.class);
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
            ? new SlidingWindowRateLimiter(properties.rateLimitPerMinute(), Duration.ofMinutes(1)) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String remote = request.getRemoteAddr();
        String path = request.getRequestURI();
        if (AccessControl.isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        String cookieToken = cookie(request, COOKIE_NAME);
        AccessControl.Request candidate = new AccessControl.Request(request.getMethod(), path, remote,
            request.getHeader("Authorization"), request.getHeader("X-Pilot-Token"), request.getParameter("token"),
            cookieToken, request.getHeader("Origin"), request.getHeader("Host"));
        AccessControl.Decision decision = control.check(candidate);
        if (!decision.allow()) {
            // 拒绝理由需要给用户看，同时写审计
            deny(response, remote, request, decision, candidate);
            return;
        }
        if (limiter != null && !limiter.allow(remote)) {
            AccessControl.Decision limited = AccessControl.Decision.reject(429, "请求过于频繁，请稍后再试");
            deny(response, remote, request, limited, candidate);
            return;
        }
        // 浏览器只会在首次访问时带 ?token=；换成 HttpOnly Cookie，页面内的 fetch/EventSource 才能继续带上
        if (properties.tokenMode() && cookieToken == null && request.getParameter("token") != null) {
            String cookie = COOKIE_NAME + "=" + properties.token()
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400" + (request.isSecure() ? "; Secure" : "");
            response.addHeader("Set-Cookie", cookie);
        }
        chain.doFilter(request, response);
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
