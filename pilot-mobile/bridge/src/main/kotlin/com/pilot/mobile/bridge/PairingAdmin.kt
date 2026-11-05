package com.pilot.mobile.bridge

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.pilot.mobile.transport.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/** 管理页面仅监听回环，临时能力令牌放 URL fragment，不进入 HTTP 请求 URL。 */
class PairingAdmin(
    private val store: PairingStore,
    private val bridge: Bridge,
    private val issue: suspend () -> PairingInvite,
) : AutoCloseable {
    @Volatile private var cachedInvite: JsonObject? = null
    private val token = PairingStore.randomSecret()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor = Executors.newFixedThreadPool(4)
    private val origin get() = "http://127.0.0.1:${server.address.port}"

    fun start(): String {
        server.executor = executor
        server.createContext("/") { exchange ->
            try { handle(exchange) }
            catch (error: Exception) {
                val message = if (error is TimeoutCancellationException)
                    "手机连接组件已启动，但连接中继超时，请检查这台电脑的网络、代理或防火墙后重试"
                    else "连接服务暂不可用，请检查网络后重试"
                runCatching { respond(exchange, 503, "application/json", buildJsonObject { put("error", message) }.toString().toByteArray()) }
            }
            finally { exchange.close() }
        }
        server.start()
        return "$origin/#$token"
    }

    private fun handle(e: HttpExchange) {
        if (e.requestHeaders.getFirst("Host") != "127.0.0.1:${server.address.port}") {
            respond(e, 403, "text/plain", byteArrayOf()); return
        }
        val path = e.requestURI.path
        if (path.startsWith("/api/")) {
            val supplied = e.requestHeaders.getFirst("X-Pilot-Pairing") ?: ""
            val requestOrigin = e.requestHeaders.getFirst("Origin")
            if (supplied.length != token.length || !MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray()) ||
                (requestOrigin != null && requestOrigin != origin)) {
                respond(e, 403, "text/plain", "请从电脑重新打开连接页面".toByteArray()); return
            }
            val body = when {
                path == "/api/status" && e.requestMethod == "GET" -> buildJsonObject {
                    put("version", Protocol.VERSION)
                    put("running", true)
                    put("maxDevices", PairingStore.MAX_DEVICES)
                    put("pairedDevice", store.pairedDevice()?.let { device -> devicePayload(device) } ?: JsonNull)
                    put("pairingState", store.pairingState())
                    put("name", store.currentInvite()?.name ?: "这台电脑")
                    put("invite", currentPayload() ?: JsonNull)
                    put("devices", buildJsonArray { store.devices().forEach { device -> add(devicePayload(device)) } })
                }.toString()
                path == "/api/devices" && e.requestMethod == "GET" ->
                    Protocol.json.encodeToString(ListSerializer(Device.serializer()), store.devices())
                path == "/api/pair" && e.requestMethod == "POST" -> {
                    val bytes = e.requestBody.readNBytes(4097)
                    require(bytes.size <= 4096)
                    val reuse = bytes.isNotEmpty() && Protocol.json.parseToJsonElement(String(bytes)).jsonObject["reuse"]?.jsonPrimitive?.booleanOrNull == true
                    val current = if (reuse) currentPayload() else null
                    (current ?: synchronized(this) {
                        if (reuse) currentPayload()?.let { return@synchronized it }
                        val invite = runBlocking { issue() }
                        val matrix = MultiFormatWriter().encode(invite.encode(), BarcodeFormat.QR_CODE, 768, 768,
                            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
                        val png = ByteArrayOutputStream()
                        MatrixToImageWriter.writeToStream(matrix, "PNG", png)
                        buildJsonObject {
                            put("code", invite.encode()); put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()))
                            put("expiresIn", 300); put("expiresAt", invite.expiresAt); put("name", invite.name)
                            put("development", invite.developmentRelays)
                        }.also { cachedInvite = it }
                    }).toString()
                }
                path == "/api/revoke" && e.requestMethod == "POST" -> {
                    val raw = e.requestBody.readNBytes(4097)
                    require(raw.size <= 4096)
                    val id = Protocol.json.parseToJsonElement(String(raw)).jsonObject["id"]!!.jsonPrimitive.content
                    bridge.revoke(id); "{}"
                }
                else -> { respond(e, 404, "text/plain", byteArrayOf()); return }
            }
            respond(e, 200, "application/json; charset=utf-8", body.toByteArray())
        } else if (e.requestMethod == "GET" && path in listOf("/", "/pairing.js", "/pairing.css")) {
            val resource = if (path == "/") "/pairing.html" else path
            val bytes = javaClass.getResourceAsStream(resource)?.use { it.readBytes() } ?: error("缺少配对界面")
            val type = when { resource.endsWith(".js") -> "text/javascript"; resource.endsWith(".css") -> "text/css"; else -> "text/html" }
            respond(e, 200, "$type; charset=utf-8", bytes)
        } else respond(e, 404, "text/plain", byteArrayOf())
    }

    private fun devicePayload(device: Device): JsonObject = buildJsonObject {
        put("id", device.id); put("name", device.name); put("pairedAt", device.pairedAt)
        put("online", bridge.online(device.id))
    }

    private fun currentPayload(): JsonObject? {
        val active = store.currentInvite() ?: return null
        return cachedInvite?.takeIf { it["code"]?.jsonPrimitive?.content == active.encode() }
    }

    private fun respond(e: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        e.responseHeaders.set("Content-Type", type)
        e.responseHeaders.set("Cache-Control", "no-store")
        e.responseHeaders.set("X-Content-Type-Options", "nosniff")
        e.responseHeaders.set("Referrer-Policy", "no-referrer")
        e.responseHeaders.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
        e.sendResponseHeaders(status, bytes.size.toLong())
        e.responseBody.use { it.write(bytes) }
    }

    override fun close() { server.stop(0); executor.shutdownNow() }
}
