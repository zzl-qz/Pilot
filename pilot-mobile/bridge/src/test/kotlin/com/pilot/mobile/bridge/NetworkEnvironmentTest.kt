package com.pilot.mobile.bridge

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.*

class NetworkEnvironmentTest {
    private fun address(value: String) = InetAddress.getByName(value).address

    @Test fun `网线WiFi代理和TUN所有组合与双栈均不改变系统选路或泄露信息`() {
        for (name in listOf("Ethernet 2", "enp3s0", "Wi-Fi", "wlan0", "en0")) {
            for (proxy in listOf(false, true)) for (tun in listOf(false, true)) {
                val interfaces = mutableListOf(InterfaceObservation(name, listOf(address("192.0.2.10"), address("2001:db8::2"))))
                if (tun) interfaces += InterfaceObservation("any-vpn-name", listOf(address("198.18.0.1")), true)
                val observed = observeProxies(if (proxy) mapOf("HTTPS_PROXY" to "http://user:password@proxy.example:7890") else emptyMap(), emptyMap(), listOf(Proxy.NO_PROXY))
                val snapshot = environmentSnapshot(interfaces, observed)
                assertEquals("available", snapshot.availability)
                assertTrue(snapshot.ipv4 && snapshot.ipv6)
                assertEquals(tun, snapshot.tunnelHint)
                assertEquals(proxy, snapshot.proxyTypes.isNotEmpty())
                val payload = snapshot.payload().toString()
                for (secret in listOf("password", "proxy.example", "192.0.2.10", "2001:db8")) assertFalse(payload.contains(secret))
                assertFalse(snapshot.payload()["explicitProxySupported"].toString().toBoolean())
            }
        }
    }

    @Test fun `IPv6单栈无网卡未知状态与普通虚拟网卡均有独立反馈`() {
        val proxy = observeProxies(emptyMap(), emptyMap(), null)
        val ipv6 = environmentSnapshot(listOf(InterfaceObservation("arbitrary", listOf(address("2001:db8::3")))), proxy)
        assertTrue(ipv6.ipv6); assertFalse(ipv6.ipv4)
        assertEquals("none", environmentSnapshot(emptyList(), proxy).availability)
        assertEquals("unknown", NetworkEnvironment.unknown().availability)
        assertFalse(environmentSnapshot(listOf(InterfaceObservation("Docker", listOf(address("172.16.1.2")))), proxy).tunnelHint)
        assertEquals("available", environmentSnapshot(listOf(InterfaceObservation("Ethernet", listOf(address("169.254.1.2")))), proxy).availability,
            "Windows 无 DHCP 时的链路本地地址不能被误报为没有网卡")
    }

    @Test fun `代理识别支持大小写SOCKS系统设置排除规则及非法值且不改变全局配置`() {
        val prior = System.getProperty("http.proxyHost")
        val selected = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system-proxy.example", 8080)))
        val observed = observeProxies(mapOf("all_proxy" to "socks5h://a:b@socks.example:1080", "NO_PROXY" to "localhost"),
            mapOf("https.proxyHost" to "configured.example", "https.proxyPort" to "7891"), selected)
        assertEquals(setOf("http", "socks"), observed.types)
        assertEquals("system-and-configured", observed.detection)
        assertEquals(prior, System.getProperty("http.proxyHost"))
        for (invalid in listOf("broken proxy", "file:///tmp/private", "http://127.0.0.1:0", "http://127.0.0.1:99999")) {
            val result = observeProxies(mapOf("HTTPS_PROXY" to invalid), emptyMap(), null)
            assertTrue(result.invalid); assertTrue(result.types.isEmpty())
        }
        assertTrue(observeProxies(mapOf("NO_PROXY" to "*"), emptyMap(), null).types.isEmpty())
    }

    @Test fun `枚举顺序与代理认证变化不误触发网络切换但地址端口变化会触发`() {
        val first = InterfaceObservation("one", listOf(address("192.0.2.10")))
        val second = InterfaceObservation("two", listOf(address("2001:db8::2")))
        fun proxy(password: String, port: Int) = observeProxies(mapOf("HTTPS_PROXY" to "http://user:$password@proxy.example:$port"), emptyMap(), null)
        val a = environmentSnapshot(listOf(first, second), proxy("one", 7890))
        assertEquals(a.fingerprint, environmentSnapshot(listOf(second, first), proxy("two", 7890)).fingerprint)
        assertNotEquals(a.fingerprint, environmentSnapshot(listOf(first, second), proxy("one", 7891)).fingerprint)
        assertNotEquals(a.fingerprint, environmentSnapshot(listOf(first), proxy("one", 7890)).fingerprint)
    }
}
