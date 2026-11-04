package com.pilot.mobile.bridge

import com.google.zxing.*
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.pilot.mobile.transport.*
import computer.iroh.EndpointTicket
import kotlinx.coroutines.runBlocking
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
        val admin = PairingAdmin(store, Bridge(endpoint, store, 9999)) {
            store.issue(EndpointTicket.fromAddr(endpoint.addr()).toString(), "测试电脑", emptyList(), false)
        }
        try {
            val url = URI(admin.start())
            val root = "http://127.0.0.1:${url.port}"
            val http = HttpClient.newHttpClient()
            fun request(path: String, method: String = "GET", auth: Boolean = true, origin: String? = null): HttpResponse<String> {
                val builder = HttpRequest.newBuilder(URI(root + path)).method(method, HttpRequest.BodyPublishers.noBody())
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
            val bytes = Base64.getDecoder().decode(payload.text("image").substringAfter(','))
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(ImageIO.read(bytes.inputStream()))))
            val decoded = MultiFormatReader().decode(bitmap).text
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
        } finally { admin.close(); endpoint.shutdown(); directory.toFile().deleteRecursively() }
    }
}
