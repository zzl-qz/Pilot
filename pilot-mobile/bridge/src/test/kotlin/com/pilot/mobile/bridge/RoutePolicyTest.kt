package com.pilot.mobile.bridge

import com.pilot.mobile.transport.Request
import kotlin.test.*

class RoutePolicyTest {
    @Test fun `事件版本与长消息引用只开放固定路由和数字参数`() {
        assertTrue(RoutePolicy.allowed(Request(path = "/api/sessions/s/events?after=2&revision=1&protocol=2")))
        assertTrue(RoutePolicy.allowed(Request(path = "/api/sessions/s/events/3?revision=1")))
        for (path in listOf("/api/sessions/s/events/3?revision=bad", "/api/sessions/s/events/../../settings", "/api/sessions/s/events?revision=1&revision=2")) {
            assertFalse(RoutePolicy.allowed(Request(path = path)))
        }
    }
    @Test fun `回执协议只开放设备令牌和确认不开放维护清理`() {
        assertTrue(RoutePolicy.allowed(Request(path = "/api/mobile/receipts/token")))
        assertTrue(RoutePolicy.allowed(Request("POST", "/api/mobile/receipts/ack", "{}")))
        val token = "r3.1800000000000.12345678-1234-1234-1234-123456789abc." + "a".repeat(64)
        assertTrue(RoutePolicy.allowed(Request("POST", "/api/sessions", "{}", requestId = token)))
        assertFalse(RoutePolicy.allowed(Request("POST", "/api/sessions", "{}", requestId = token + "\n")))
        for (path in listOf("/api/maintenance/mobile-receipts", "/api/mobile/receipts/token?peer=other")) {
            assertFalse(RoutePolicy.allowed(Request(path = path)))
        }
        assertFalse(RoutePolicy.allowed(Request("POST", "/api/maintenance/mobile-receipts/cleanup", "{}")))
    }

    @Test fun `条件快照只允许精确只读路由与固定格式版本`() {
        val revision = "a".repeat(64)
        assertTrue(RoutePolicy.allowed(Request(path = "/api/mobile/workbench", snapshotRevision = revision)))
        assertTrue(RoutePolicy.allowed(Request(path = "/api/mobile/workbench?revision=$revision")))
        assertFalse(RoutePolicy.allowed(Request(path = "/api/sessions", snapshotRevision = revision)))
        assertFalse(RoutePolicy.allowed(Request("POST", "/api/mobile/workbench", snapshotRevision = revision)))
        for (invalid in listOf("", "a".repeat(63), "A".repeat(64), "$revision&redirect=http://evil.test")) {
            assertFalse(RoutePolicy.allowed(Request(path = "/api/mobile/workbench", snapshotRevision = invalid)))
            assertFalse(RoutePolicy.allowed(Request(path = "/api/mobile/workbench?revision=$invalid")))
        }
        assertFalse(RoutePolicy.allowed(Request(path = "/api/mobile/workbench?revision=$revision&revision=$revision")))
        assertFalse(RoutePolicy.allowed(Request(path = "/api/mobile/workbench?revision=$revision", snapshotRevision = revision)))
    }

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
