package com.pilot.mobile.transport

import computer.iroh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 端点公钥由配对票据固定。断线只重建连接，不自动重放 POST 等修改请求。 */
class RemoteClient(
    private val endpoint: Endpoint,
    private val ticket: String,
    private val requestTimeoutMillis: Long = 45_000,
) {
    private val mutex = Mutex()
    private var connection: Connection? = null

    private suspend fun connect(): Connection = mutex.withLock {
        connection?.takeIf { it.closeReason() == null } ?: withTimeout(20_000) {
            val address = EndpointTicket.fromString(ticket).endpointAddr()
            endpoint.connect(address, Protocol.alpn).also { connection = it }
        }
    }

    fun pathLabel(): String {
        val path = connection?.paths()?.firstOrNull { it.isSelected }
        return when {
            path?.isIp == true -> "直连 · ${path.rttMs} ms"
            path?.isRelay == true -> "中继 · ${path.rttMs} ms"
            else -> "正在连接"
        }
    }

    suspend fun request(request: Request): Pair<ResponseHead, String> = try {
        withTimeout(requestTimeoutMillis) {
            val bytes = ByteArrayOutputStream()
            val head = transfer(request) { chunk ->
                require(bytes.size() + chunk.size <= Protocol.MAX_RESPONSE) { "响应过大，请在电脑端查看" }
                bytes.write(chunk)
            }
            head to bytes.toString(Charsets.UTF_8.name())
        }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("请求超时，请先核对电脑端是否已收到指令", e)
    }

    /** SSE 原样传递，回到前台后由调用方使用事件游标续接。 */
    suspend fun transfer(request: Request, receive: suspend (ByteArray) -> Unit): ResponseHead {
        val stream = try { connect().openBi() }
        catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("连接超时，请确认电脑在线并检查网络", e)
        }
        val send = stream.send()
        val recv = stream.recv()
        try {
            Protocol.write(send, Protocol.json.encodeToString(Request.serializer(), request).toByteArray())
            send.finish()
            val head = Protocol.json.decodeFromString(ResponseHead.serializer(), String(withTimeout(30_000) { Protocol.read(recv) }))
            while (true) {
                val chunk = withTimeout(35_000) { Protocol.read(recv) }
                if (chunk.isEmpty()) break
                receive(chunk)
            }
            return head
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("连接暂时中断，正在等待重新连接", e)
        } finally {
            withContext(NonCancellable) {
                runCatching { recv.stop(0u) }
                runCatching { send.reset(0u) }
            }
            recv.close(); send.close(); stream.close()
        }
    }

    fun disconnect() {
        connection?.close(0, "disconnect".toByteArray())
        connection?.close()
        connection = null
    }
}

/** 同一配置用于 Android 和桌面；测试可关闭外部发现与中继。 */
suspend fun createEndpoint(key: ByteArray, relays: List<String> = emptyList(), development: Boolean = true): Endpoint {
    val mode = when {
        relays.isNotEmpty() -> RelayMode.customFromUrls(relays)
        development -> RelayMode.defaultMode()
        else -> RelayMode.disabled()
    }
    return Endpoint.bind(EndpointOptions(
        preset = if (development) presetN0() else presetMinimal(),
        secretKey = key, alpns = listOf(Protocol.alpn), relayMode = mode,
    ))
}
