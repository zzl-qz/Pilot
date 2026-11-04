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
    private val diagnostic: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var connection: Connection? = null

    private fun trace(message: String) { runCatching { diagnostic(message) } }

    private suspend fun connect(): Connection = mutex.withLock {
        connection?.takeIf { it.closeReason() == null } ?: withTimeout(20_000) {
            trace("connect.start")
            val address = EndpointTicket.fromString(ticket).use { it.endpointAddr() }
            try { endpoint.connect(address, Protocol.alpn).also { connection = it; trace("connect.ready ${pathLabel()}") } }
            finally { address.close() }
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
        trace("request.timeout method=${request.method}")
        throw IOException(requestTimeoutMessage(request.method), e)
    }

    /** SSE 原样传递，回到前台后由调用方使用事件游标续接。 */
    suspend fun transfer(request: Request, receive: suspend (ByteArray) -> Unit): ResponseHead {
        val started = System.nanoTime()
        var received = 0L
        var wireBytes = 0L
        trace("transfer.start method=${request.method}")
        val active = try { connect() }
        catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("连接超时，请确认电脑在线并检查网络", e)
        } catch (e: IrohException) {
            trace("connect.failed kind=${e.kind()}")
            throw e
        }
        val stream = try { active.openBi() }
        catch (e: TimeoutCancellationException) {
            invalidate(active)
            throw e
        } catch (e: IrohException) {
            invalidate(active)
            throw e
        }
        val send = stream.send()
        val recv = stream.recv()
        try {
            Protocol.write(send, Protocol.json.encodeToString(Request.serializer(), request.copy(acceptGzipFrames = true)).toByteArray())
            send.finish()
            val head = Protocol.json.decodeFromString(ResponseHead.serializer(), String(withTimeout(30_000) { Protocol.read(recv) }))
            trace("response.head status=${head.status} ${pathLabel()}")
            while (true) {
                val chunk = withTimeout(35_000) { Protocol.read(recv, if (head.frameEncoding.isEmpty()) Protocol.MAX_FRAME else FrameCodec.MAX_ENCODED_FRAME) }
                if (chunk.isEmpty()) break
                val decoded = if (head.frameEncoding.isEmpty()) chunk else withContext(Dispatchers.Default) { FrameCodec.decode(chunk, head.frameEncoding) }
                received += decoded.size
                wireBytes += chunk.size
                require(head.contentType.startsWith("text/event-stream") || received <= Protocol.MAX_RESPONSE) { "响应过大，请在电脑端查看" }
                receive(decoded)
            }
            return head
        } catch (e: TimeoutCancellationException) {
            invalidate(active)
            currentCoroutineContext().ensureActive()
            throw IOException("连接暂时中断，正在等待重新连接", e)
        } catch (e: IOException) {
            invalidate(active)
            throw e
        } catch (e: IrohException) {
            invalidate(active)
            trace("transfer.failed kind=${e.kind()}")
            throw e
        } finally {
            trace("transfer.end bytes=$received wire=$wireBytes ms=${(System.nanoTime() - started) / 1_000_000}")
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { recv.stop(0u) }
                runCatching { send.reset(0u) }
                runCatching { recv.close() }
                runCatching { send.close() }
                runCatching { stream.close() }
            }
        }
    }

    suspend fun disconnect() = invalidate()

    private suspend fun invalidate(expected: Connection? = null) = withContext(NonCancellable) {
        val previous = mutex.withLock {
            // 并发请求的旧错误不能关闭另一个请求刚刚建立的新连接。
            if (expected != null && connection !== expected) return@withContext
            connection.also { connection = null }
        }
        trace("connection.invalidated")
        // FFI 释放可能等待在途回调；不能阻塞 Android 主线程上的其他协程。
        withContext(NonCancellable + Dispatchers.IO) {
            previous?.let {
                runCatching { it.close(0, "disconnect".toByteArray()) }
                runCatching { it.close() }
            }
        }
        trace("connection.closed")
    }
}

internal fun requestTimeoutMessage(method: String): String = if (method == "GET")
    "读取数据超时，正在尝试重新连接电脑" else "请求超时，请先核对电脑端是否已收到指令"

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
