package com.pilot.mobile.bridge.pairing

import com.pilot.mobile.bridge.Bridge
import com.google.zxing.*
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.pilot.mobile.transport.*
import computer.iroh.EndpointTicket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class PairingAdminTest {
    @Test fun `管理接口鉴权来源校验与二维码可解码`() = runBlocking {
        val directory = Files.createTempDirectory("pilot-admin")
        val store = PairingStore(directory)
        val endpoint = createEndpoint(store.key, development = false)
        var simulateRelayTimeout = false
        val admin = PairingAdmin(store, Bridge(endpoint, store, 9999), relayConfigured = true) {
            if (simulateRelayTimeout) withTimeout(1) { delay(100) }
            store.issue(EndpointTicket.fromAddr(endpoint.addr()).toString(), "测试电脑", emptyList(), false)
        }
        try {
            val url = URI(admin.start())
            val root = "http://127.0.0.1:${url.port}"
            val http = HttpClient.newHttpClient()
            fun request(path: String, method: String = "GET", auth: Boolean = true, origin: String? = null, body: String? = null): HttpResponse<String> {
                val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
                val builder = HttpRequest.newBuilder(URI(root + path)).method(method, publisher)
                if (auth) builder.header("X-Pilot-Pairing", url.fragment)
                if (origin != null) builder.header("Origin", origin)
                return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }
            assertEquals(403, request("/api/devices", auth = false).statusCode())
            assertEquals(403, request("/api/pair", "POST", origin = "https://evil.test").statusCode())
            assertEquals(404, request("/api/pair").statusCode())
            val response = request("/api/pair", "POST", origin = root)
            assertEquals(200, response.statusCode())
            assertTrue(response.headers().firstValue("Content-Security-Policy").get().contains("frame-ancestors 'none'"))
            val payload = Protocol.json.parseToJsonElement(response.body()).jsonObject
            assertEquals("relay-enabled", payload.text("connectionMode"))
            val reused = Protocol.json.parseToJsonElement(request("/api/pair", "POST", origin = root, body = """{"reuse":true}""").body()).jsonObject
            assertEquals(payload.text("code"), reused.text("code"))
            assertEquals(503, request("/api/pair", "POST", origin = root, body = "x".repeat(4097)).statusCode())
            val bytes = Base64.getDecoder().decode(payload.text("image").substringAfter(','))
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(ImageIO.read(bytes.inputStream()))))
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
            val decoded = MultiFormatReader().decode(bitmap, hints).text
            assertEquals(payload.text("code"), decoded)
            assertEquals("测试电脑", PairingInvite.decode(decoded).name)
            assertEquals("[]", request("/api/devices").body())
            val invite = PairingInvite.decode(decoded)
            assertTrue(store.pair("phone-1", invite.secret, "我的手机"))
            val status = Protocol.json.parseToJsonElement(request("/api/status").body()).jsonObject
            assertEquals(16, status["maxDevices"]!!.jsonPrimitive.int)
            assertEquals("paired", status.text("pairingState"))
            assertEquals("我的手机", status["pairedDevice"]!!.jsonObject.text("name"))
            assertEquals(JsonNull, status["invite"])
            assertFalse(status.toString().contains(invite.secret))
            assertEquals(200, request("/api/revoke", "POST", origin = root, body = """{"id":"phone-1"}""").statusCode())
            assertEquals("[]", request("/api/devices").body())
            repeat(24) {
                val rotated = Protocol.json.parseToJsonElement(request("/api/pair", "POST", origin = root).body()).jsonObject
                val image = Base64.getDecoder().decode(rotated.text("image").substringAfter(','))
                val pixels = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(ImageIO.read(image.inputStream()))))
                assertEquals(rotated.text("code"), MultiFormatReader().decode(pixels, hints).text)
            }
            simulateRelayTimeout = true
            val failed = request("/api/pair", "POST", origin = root)
            assertEquals(503, failed.statusCode())
            assertTrue(Protocol.json.parseToJsonElement(failed.body()).jsonObject.text("error").contains("后端已配置的中继"))
            assertFalse(failed.body().contains(invite.secret))
            assertEquals(200, request("/api/status").statusCode(), "配对超时不能误报为连接进程已退出")
        } finally { admin.close(); endpoint.shutdown(); directory.toFile().deleteRecursively() }
    }
}
