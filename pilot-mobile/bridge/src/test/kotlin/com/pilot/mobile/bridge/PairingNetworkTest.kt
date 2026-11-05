package com.pilot.mobile.bridge

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.pilot.mobile.transport.*
import computer.iroh.EndpointAddr
import computer.iroh.EndpointTicket
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class PairingNetworkTest {
    @Test fun `公网准备和失败均不阻塞出码路由恢复不延长授权且单次消费不复活`() = runBlocking {
        val directory = Files.createTempDirectory("pilot-network")
        val store = PairingStore(directory)
        val endpoint = createEndpoint(store.key, development = false)
        val failed = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        var ticket = endpoint.addr().use { EndpointTicket.fromAddr(it).use { value -> value.toString() } }
        val network = PairingNetwork(store, "automatic", { ticket }, online = {
            if (attempts.incrementAndGet() == 1) { failed.await(); error("simulated DNS failure") }
            recovered.await()
        }, retryMillis = 10, diagnose = { " TUN 测试诊断" })
        val admin = PairingAdmin(store, Bridge(endpoint, store, 9999), network = network::snapshot) {
            network.issue("电脑", emptyList(), true)
        }
        try {
            val uri = URI(admin.start())
            val root = "http://127.0.0.1:${uri.port}"
            val http = HttpClient.newHttpClient()
            fun request(path: String, method: String = "GET"): JsonObject {
                val request = HttpRequest.newBuilder(URI(root + path)).header("X-Pilot-Pairing", uri.fragment)
                    .method(method, HttpRequest.BodyPublishers.noBody()).build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                return Protocol.json.parseToJsonElement(response.body()).jsonObject
            }
            val initial = request("/api/pair", "POST")
            assertEquals("preparing", initial.text("networkState"), "出码不能等待尚未完成的公网探测")
            val invite = PairingInvite.decode(initial.text("code"))
            assertTrue(invite.developmentRelays)
            failed.complete(Unit)
            withTimeout(2_000) { while (network.snapshot().text("networkState") != "unavailable") delay(5) }
            val unavailable = request("/api/status")
            assertTrue(unavailable.text("networkMessage").contains("TUN"))
            assertEquals(initial.text("code"), unavailable["invite"]!!.jsonObject.text("code"))
            endpoint.id().use { id ->
                EndpointAddr(id, "https://relay.example.com", listOf("127.0.0.1:12345")).use { address ->
                    ticket = EndpointTicket.fromAddr(address).use { it.toString() }
                }
            }
            recovered.complete(Unit)
            withTimeout(2_000) { while (network.snapshot().text("networkState") != "ready") delay(5) }
            val updated = request("/api/status")["invite"]!!.jsonObject
            val routed = PairingInvite.decode(updated.text("code"))
            assertNotEquals(initial.text("code"), updated.text("code"))
            assertEquals(invite.secret, routed.secret)
            assertEquals(invite.expiresAt, routed.expiresAt)
            val bytes = Base64.getDecoder().decode(updated.text("image").substringAfter(','))
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(ImageIO.read(bytes.inputStream()))))
            assertEquals(updated.text("code"), MultiFormatReader().decode(bitmap).text)
            assertTrue(store.pair("phone", invite.secret, "手机"), "网络地址变化不能使已扫描的授权失效")
            assertEquals(JsonNull, request("/api/status")["invite"])
            assertEquals("paired", store.pairingState())
            assertFalse(store.pair("second-phone", invite.secret, "另一台"))
        } finally { admin.close(); network.close(); endpoint.shutdown(); directory.toFile().deleteRecursively() }
    }

    @Test fun `超时进入不可用并重试关闭后停止探测`() = runBlocking {
        val directory = Files.createTempDirectory("pilot-network-timeout")
        val store = PairingStore(directory)
        val calls = AtomicInteger()
        val network = PairingNetwork(store, "automatic", { "test-ticket" },
            online = { calls.incrementAndGet(); awaitCancellation() }, timeoutMillis = 20, retryMillis = 5)
        try {
            withTimeout(2_000) { while (calls.get() < 2) delay(5) }
            assertEquals("unavailable", network.snapshot().text("networkState"))
            network.close()
            delay(50)
            val stopped = calls.get()
            delay(50)
            assertEquals(stopped, calls.get())
        } finally { network.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun `直连没有公网探测且过期路由刷新不重新开放授权`() {
        val directory = Files.createTempDirectory("pilot-network-expiry")
        var now = 1_000L
        val store = PairingStore(directory) { now }
        val network = PairingNetwork(store, "direct-only", { "updated-ticket" }, online = null)
        try {
            val invite = network.issue("电脑", emptyList(), false)
            assertEquals("local", network.snapshot().text("networkState"))
            now = invite.expiresAt
            network.snapshot()
            assertNull(store.currentInvite())
            assertEquals("expired", store.pairingState())
            assertFalse(store.pair("phone", invite.secret, "手机"))
        } finally { network.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun `虚拟DNS诊断覆盖整个198点18斜杠15范围且不误报正常IP或IPv6`() {
        assertTrue(dnsHint(listOf(byteArrayOf(198.toByte(), 18, 0, 1))).contains("虚拟 DNS"))
        assertTrue(dnsHint(listOf(byteArrayOf(198.toByte(), 19, 127, 1))).contains("虚拟 DNS"))
        assertFalse(dnsHint(listOf(byteArrayOf(198.toByte(), 20, 0, 1), ByteArray(16))).contains("虚拟 DNS"))
    }
}
