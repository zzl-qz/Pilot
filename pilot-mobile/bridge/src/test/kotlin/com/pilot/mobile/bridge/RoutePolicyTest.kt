package com.pilot.mobile.bridge

import com.pilot.mobile.transport.Request
import kotlin.test.*

class RoutePolicyTest {
    @Test fun `移动工作流可用且禁止任意代理和敏感设置`() {
        for (path in listOf("/api/sessions", "/api/sessions/ses_1/events?after=2", "/api/sessions/ses_1/history?limit=300&before=400")) {
            assertTrue(RoutePolicy.allowed(Request(path = path)), path)
        }
        assertTrue(RoutePolicy.allowed(Request("POST", "/api/sessions/ses_1/messages", "{}")))
        for (path in listOf("http://evil.test/api/sessions", "//evil.test/api/sessions", "/api/settings", "/api/desktop/browser-window", "/api/sessions/../settings", "/api/sessions/%2e%2e/settings", "/api/sessions#x", "/api/sessions?redirect=http://evil.test", "/api/sessions/ses_1/events?token=secret")) {
            assertFalse(RoutePolicy.allowed(Request(path = path)), path)
        }
        assertTrue(RoutePolicy.allowed(Request("PATCH", "/api/sessions/ses_1/archive", "{\"archived\":true}")))
        assertTrue(RoutePolicy.allowed(Request("PUT", "/api/sessions/ses_1/queue/0", "{}")))
        assertTrue(RoutePolicy.allowed(Request("DELETE", "/api/sessions/ses_1/queue/0?revision=abc")))
        assertTrue(RoutePolicy.allowed(Request(path = "/api/workspaces/browse?path=C%3A%5CUsers%5Ctest")))
        assertTrue(RoutePolicy.allowed(Request(path = "/api/sessions/ses_1/products/p_1/download")))
        for (path in listOf("/api/mobile/connection", "/api/mobile/connection/pair", "/api/workspaces/browse?path=a&path=b", "/api/sessions/ses_1/history?after=1&after=2")) {
            assertFalse(RoutePolicy.allowed(Request(path = path)), path)
        }
        assertFalse(RoutePolicy.allowed(Request("DELETE", "/api/sessions/ses_1")))
        assertFalse(RoutePolicy.allowed(Request("POST", "/api/sessions", "a".repeat(12 * 1024 * 1024 + 1))))
    }
}
