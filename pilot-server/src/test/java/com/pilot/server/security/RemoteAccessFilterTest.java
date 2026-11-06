package com.pilot.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.pilot.server.config.PilotDataDir;

class RemoteAccessFilterTest {
    @TempDir
    Path data;

    private RemoteAccessFilter filter(String mode, int limit) {
        var properties = new SecurityProperties(mode, "test-secret", null, limit, false);
        return new RemoteAccessFilter(properties, new AccessAudit(new PilotDataDir(data.toString()), properties));
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.168.1.9");
        request.setScheme("https");
        request.addHeader("Host", "pilot.test");
        request.addHeader("Origin", "https://pilot.test");
        return request;
    }

    private MockHttpServletResponse run(RemoteAccessFilter filter, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void publicLoginPageHasNoDataOrSecretAndApiRemainsProtected() throws Exception {
        var filter = filter("token", 0);
        var page = run(filter, request("GET", "/connect"));
        assertEquals(200, page.getStatus());
        assertTrue(page.getContentAsString().contains("访问令牌"));
        assertFalse(page.getContentAsString().contains("test-secret"));
        assertFalse(page.getContentAsString().contains("__NONCE__"));
        assertTrue(page.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertEquals(401, run(filter, request("GET", "/api/sessions/s/products")).getStatus());
        assertEquals(401, run(filter, request("GET", "/connect/extra")).getStatus());
    }

    @Test
    void browserHomeRedirectsButApiReturnsJson() throws Exception {
        var filter = filter("token", 0);
        var browser = request("GET", "/");
        browser.addHeader("Accept", "text/html");
        assertEquals("/connect", run(filter, browser).getRedirectedUrl());
        assertEquals(401, run(filter, request("GET", "/api/sessions")).getStatus());
        assertEquals(403, run(filter("local", 0), browser).getStatus());
    }

    @Test
    void headerLoginSetsHttpOnlyCookieAndLogoutExpiresIt() throws Exception {
        var filter = filter("token", 0);
        var login = request("POST", "/api/access/login");
        login.setSecure(true);
        login.addHeader("X-Pilot-Token", "test-secret");
        var response = run(filter, login);
        assertEquals(204, response.getStatus());
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly; SameSite=Strict"));
        assertTrue(cookie.contains("Secure"));
        var dataRequest = request("GET", "/api/sessions");
        dataRequest.setCookies(new Cookie("PILOT_ACCESS", "test-secret"));
        var chain = new MockFilterChain();
        filter.doFilter(dataRequest, new MockHttpServletResponse(), chain);
        assertEquals(dataRequest, chain.getRequest());
        var logout = request("POST", "/api/access/logout");
        logout.setCookies(new Cookie("PILOT_ACCESS", "test-secret"));
        assertTrue(run(filter, logout).getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test
    void invalidOrCrossOriginLoginNeverSetsCookieAndFailuresAreRateLimited() throws Exception {
        var filter = filter("token", 2);
        var bad = request("POST", "/api/access/login");
        bad.addHeader("X-Pilot-Token", "wrong");
        var denied = run(filter, bad);
        assertEquals(401, denied.getStatus());
        assertNull(denied.getHeader("Set-Cookie"));
        var foreign = request("POST", "/api/access/login");
        foreign.addHeader("X-Pilot-Token", "test-secret");
        foreign.removeHeader("Origin");
        foreign.addHeader("Origin", "https://foreign.test");
        assertEquals(403, run(filter, foreign).getStatus());
        assertEquals(429, run(filter, request("POST", "/api/access/login")).getStatus());
    }

    @Test
    void legacyTokenUrlRedirectsToCleanHomeButSseKeepsWorking() throws Exception {
        var filter = filter("token", 0);
        var legacy = request("GET", "/");
        legacy.setParameter("token", "test-secret");
        var response = run(filter, legacy);
        assertEquals("/", response.getRedirectedUrl());
        assertTrue(response.getHeader("Set-Cookie").contains("PILOT_ACCESS="));
        var sse = request("GET", "/api/sessions/s/events");
        sse.setParameter("token", "test-secret");
        var chain = new MockFilterChain();
        filter.doFilter(sse, new MockHttpServletResponse(), chain);
        assertEquals(sse, chain.getRequest());
    }

    @Test
    void localModeRejectsForeignHeadersOnPrivateAndPublicRoutes() throws Exception {
        var filter = filter("local", 0);
        for (String path : List.of("/api/sessions", "/api/access/login", "/api/health", "/connect")) {
            var foreign = request("POST", path);
            foreign.setRemoteAddr("127.0.0.1");
            foreign.removeHeader("Host");
            foreign.addHeader("Host", "127.0.0.1:9999");
            assertEquals(403, run(filter, foreign).getStatus());
            foreign.removeHeader("Origin");
            foreign.addHeader("Origin", "https://rebound.example");
            foreign.removeHeader("Host");
            foreign.addHeader("Host", "rebound.example");
            assertEquals(403, run(filter, foreign).getStatus());
        }
    }

    @Test
    void localCliSameOriginAndFetchMetadataAreHandledExplicitly() throws Exception {
        var filter = filter("local", 0);
        var cli = new MockHttpServletRequest("GET", "/api/sessions");
        cli.setRemoteAddr("127.0.0.1");
        cli.addHeader("Host", "localhost:9999");
        var chain = new MockFilterChain();
        filter.doFilter(cli, new MockHttpServletResponse(), chain);
        assertEquals(cli, chain.getRequest());
        cli.addHeader("Sec-Fetch-Site", "cross-site");
        assertEquals(403, run(filter, cli).getStatus());
        cli.removeHeader("Sec-Fetch-Site");
        cli.addHeader("Origin", "http://localhost:9999");
        var sameOrigin = new MockFilterChain();
        filter.doFilter(cli, new MockHttpServletResponse(), sameOrigin);
        assertEquals(cli, sameOrigin.getRequest());
        cli.addHeader("Origin", "https://evil.example");
        assertEquals(403, run(filter, cli).getStatus());
    }
}
