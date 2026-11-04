package com.pilot.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AccessControlTest {

    private static SecurityProperties local() {
        return new SecurityProperties("local", "", null, 0, true);
    }

    private static SecurityProperties token(String token) {
        return new SecurityProperties("token", token, List.of("https://pilot.example.com"), 0, true);
    }

    private static AccessControl.Request request(String remote) {
        return new AccessControl.Request("GET", "/api/sessions", remote, null, null, null, null, "127.0.0.1:8080");
    }

    @Test
    void localModeAllowsLoopbackOnly() {
        AccessControl control = new AccessControl(local());

        assertTrue(control.check(request("127.0.0.1")).allow());
        assertTrue(control.check(request("::1")).allow());

        AccessControl.Decision denied = control.check(request("192.168.1.20"));
        assertFalse(denied.allow());
        assertEquals(403, denied.status());
        assertTrue(denied.reason().contains("只允许本机"), denied.reason());
    }

    @Test
    void tokenModeRefusesWithoutConfiguredToken() {
        AccessControl control = new AccessControl(token(""));
        AccessControl.Decision decision = control.check(request("192.168.1.20"));

        assertEquals(503, decision.status());
        assertTrue(decision.reason().contains("未配置访问令牌"));
    }

    @Test
    void tokenModeRequiresTheRightToken() {
        AccessControl control = new AccessControl(token("s3cret-token"));

        assertEquals(401, control.check(request("192.168.1.20")).status());
        assertEquals(401, control.check(new AccessControl.Request("GET", "/api/sessions", "192.168.1.20",
            "Bearer wrong", null, null, null, "192.168.1.20:8080")).status());
        assertTrue(control.check(new AccessControl.Request("GET", "/api/sessions", "192.168.1.20",
            "Bearer s3cret-token", null, null, null, "192.168.1.20:8080")).allow());
    }

    @Test
    void sseMayPassTokenInQueryString() {
        AccessControl control = new AccessControl(token("s3cret-token"));
        assertTrue(control.check(new AccessControl.Request("GET", "/api/sessions/s1/events", "192.168.1.20", null, null,
            "s3cret-token", null, "192.168.1.20:8080")).allow());
    }

    @Test
    void sseMayPassTokenInQueryStringAndCookieAfterwards() {
        AccessControl control = new AccessControl(token("s3cret-token"));
        AccessControl.Request pageLoad = new AccessControl.Request("GET", "/", "192.168.1.20", null, null,
            "s3cret-token", null, "http://192.168.1.20:8080", "192.168.1.20:8080");
        assertTrue(control.check(pageLoad).allow());

        // 首次带 ?token= 之后，浏览器用 HttpOnly Cookie 继续访问页面内的 fetch/EventSource
        AccessControl.Request withCookie = new AccessControl.Request("GET", "/api/sessions/s1/events", "192.168.1.20",
            null, null, null, "s3cret-token", null, "192.168.1.20:8080");
        assertTrue(control.check(withCookie).allow());
    }

    @Test
    void foreignBrowserOriginIsRejected() {
        AccessControl control = new AccessControl(token("s3cret-token"));
        AccessControl.Decision decision = control.check(new AccessControl.Request("POST", "/api/messages",
            "192.168.1.20", "Bearer s3cret-token", null, null, "http://evil.example", "192.168.1.20:8080"));

        assertEquals(403, decision.status());
        assertTrue(decision.reason().contains("来源"), decision.reason());
    }

    @Test
    void configuredOriginIsAllowed() {
        AccessControl control = new AccessControl(token("s3cret-token"));
        assertTrue(control.check(new AccessControl.Request("POST", "/api/messages", "192.168.1.20",
            "Bearer s3cret-token", null, null, "https://pilot.example.com", "192.168.1.20:8080")).allow());
    }

    @Test
    void loopbackDetectionHandlesIpv6AndZones() {
        assertTrue(AccessControl.isLoopback("127.0.0.1"));
        assertTrue(AccessControl.isLoopback("127.8.9.10"));
        assertTrue(AccessControl.isLoopback("[::1]"));
        assertFalse(AccessControl.isLoopback("fe80::1%lo0"));
        assertFalse(AccessControl.isLoopback("10.0.0.2"));
        assertFalse(AccessControl.isLoopback(null));
    }
}
