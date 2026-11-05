package com.pilot.mobile.transport

import kotlinx.coroutines.*
import kotlin.test.*

class NetworkRecoveryTest {
    @Test fun `网络切换合并并等待写请求结束离线不重放提交`() = runBlocking {
        var busy = true
        var recovered = 0
        var offline = 0
        val monitor = NetworkRecovery(this, { busy }, { offline++ }, { recovered++; true }, debounceMillis = 10)
        try {
            monitor.changed("wifi", true)
            monitor.changed("offline", false)
            monitor.changed("ethernet", true)
            monitor.changed("vpn", true)
            delay(100)
            assertEquals(0, recovered, "写请求未结束前不能拆掉连接")
            assertEquals(1, offline)
            busy = false
            withTimeout(1_000) { while (recovered == 0) delay(10) }
            assertEquals(1, recovered)
            monitor.changed("vpn", true)
            delay(50)
            assertEquals(1, recovered, "同一网络的重复通知不能反复重连")
        } finally { monitor.close() }
    }

    @Test fun `网络再次断开和生命周期关闭均取消等待中的恢复`() = runBlocking {
        var busy = true
        var recovered = 0
        val monitor = NetworkRecovery(this, { busy }, {}, { recovered++; true }, debounceMillis = 10)
        try {
        monitor.changed("one", true); monitor.changed("two", true)
        delay(20); monitor.changed("offline", false); busy = false
        delay(150)
        assertEquals(0, recovered)
        monitor.changed("three", true); monitor.close()
        delay(50)
        assertEquals(0, recovered)
        } finally { monitor.close() }
    }
}
