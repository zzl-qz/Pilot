package com.pilot.mobile.transport

import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.Base64

/** 每条 QUIC 双向流对应一个请求；帧有长度上限，零长度帧表示响应结束。 */
object Protocol {
    const val VERSION = 2
    val alpn = "pilot/remote/2".toByteArray()
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val MAX_FRAME = 16 * 1024 * 1024
    const val MAX_RESPONSE = 16 * 1024 * 1024

    suspend fun write(stream: SendStream, bytes: ByteArray) {
        require(bytes.size <= MAX_FRAME) { "消息超过传输上限" }
        stream.writeAll(ByteBuffer.allocate(4).putInt(bytes.size).array())
        if (bytes.isNotEmpty()) stream.writeAll(bytes)
    }

    suspend fun read(stream: RecvStream, maxBytes: Int = MAX_FRAME): ByteArray {
        val size = ByteBuffer.wrap(stream.readExact(4u)).int
        require(size in 0..maxBytes) { "无效的消息长度" }
        return if (size == 0) byteArrayOf() else stream.readExact(size.toUInt())
    }
}

@Serializable
data class Request(
    val method: String = "GET",
    val path: String = "/api/sessions",
    val body: String = "",
    val pairingSecret: String? = null,
    val deviceName: String? = null,
    val requestId: String? = null,
    val acceptGzipFrames: Boolean = false,
    val snapshotRevision: String? = null,
)

@Serializable
data class ResponseHead(val status: Int, val contentType: String = "application/json", val frameEncoding: String = "")

@Serializable
data class PairingInvite(
    val version: Int = Protocol.VERSION,
    val ticket: String,
    val secret: String,
    val expiresAt: Long,
    val name: String,
    val relays: List<String> = emptyList(),
    val developmentRelays: Boolean = false,
) {
    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Protocol.json.encodeToString(serializer(), this).toByteArray())

    companion object {
        private const val PREFIX = "pilot://pair/"
        fun decode(value: String, now: Long = System.currentTimeMillis()): PairingInvite {
            val text = value.trim()
            require(text.startsWith(PREFIX) && text.length <= 8192) { "这不是 Pilot 配对码" }
            val invite = try {
                Protocol.json.decodeFromString(serializer(), String(Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))))
            } catch (_: Exception) { throw IllegalArgumentException("配对码损坏，请重新扫码") }
            require(invite.version == Protocol.VERSION) { "配对协议不兼容，请更新 App" }
            require(invite.expiresAt > now && invite.expiresAt <= now + 10 * 60_000) { "配对码已过期或手机时间不正确，请重新生成并检查自动时间设置" }
            require(invite.secret.matches(Regex("[A-Za-z0-9_-]{43}"))) { "配对凭证无效" }
            require(invite.ticket.length in 20..4096 && invite.name.length in 1..80) { "配对信息无效" }
            require(invite.relays.size <= 4) { "中继配置无效" }
            invite.relays.forEach { relay ->
                val uri = java.net.URI(relay)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                    "中继必须使用 HTTPS"
                }
            }
            return invite
        }
    }
}
