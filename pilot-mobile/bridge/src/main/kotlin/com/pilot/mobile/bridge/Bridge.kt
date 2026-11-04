package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class Bridge(
    val endpoint: Endpoint,
    private val pairing: PairingStore,
    private val port: Int,
    private val token: String = "",
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val slots = Semaphore(16)

    suspend fun serve() = supervisorScope {
        while (isActive) {
            val incoming = endpoint.acceptNext() ?: break
            if (!slots.tryAcquire()) { incoming.refuse(); incoming.close(); continue }
            launch {
                var connection: Connection? = null
                try {
                    connection = withTimeout(10_000) { incoming.accept().connect() }
                    connections.add(connection)
                    connection.setMaxConcurrentBiStreams(4u)
                    connection.setMaxConcurrentUniStreams(0u)
                    serveConnection(connection)
                } catch (_: Exception) {
                    // 未认证、断网与客户端退出不记录票据或请求内容。
                } finally {
                    connection?.let { connections.remove(it); it.close(0, byteArrayOf()); it.close() }
                    incoming.close()
                    slots.release()
                }
            }
        }
    }

    private suspend fun serveConnection(connection: Connection) = supervisorScope {
        val peer = connection.remoteId().toString()
        while (isActive) {
            val stream = withTimeout(if (pairing.isTrusted(peer)) 120_000 else 15_000) { connection.acceptBi() }
            launch(Dispatchers.IO) { handle(peer, stream) }
        }
    }

    fun online(id: String): Boolean = connections.any { it.remoteId().toString() == id && it.closeReason() == null }

    fun revoke(id: String) {
        pairing.revoke(id)
        connections.filter { it.remoteId().toString() == id }.forEach { it.close(1, "revoked".toByteArray()) }
    }

    private suspend fun handle(peer: String, stream: BiStream) {
        val send = stream.send()
        val recv = stream.recv()
        try {
            val request = withTimeout(if (pairing.isTrusted(peer)) 60_000 else 10_000) {
                Protocol.json.decodeFromString(Request.serializer(), String(Protocol.read(recv, if (pairing.isTrusted(peer)) Protocol.MAX_FRAME else 16_384)))
            }
            if (request.path == "/pair" && request.method == "POST") {
                val accepted = pairing.pair(peer, request.pairingSecret, request.deviceName)
                reply(send, if (accepted) 200 else 401, if (accepted) "{\"paired\":true}" else "{\"error\":\"配对码失效或已使用，请重新生成\"}")
            } else if (!pairing.isTrusted(peer)) {
                reply(send, 401, "{\"error\":\"设备未配对或已被移除\"}")
            } else if (!RoutePolicy.allowed(request)) {
                reply(send, 403, "{\"error\":\"移动端不支持此操作\"}")
            } else {
                proxy(peer, request, send)
            }
        } catch (_: Exception) {
            // 响应可能已开始；直接重置，客户端不能把截断响应当成成功。
            withContext(NonCancellable) { runCatching { send.reset(1u) } }
        } finally {
            withContext(NonCancellable) { runCatching { recv.stop(0u) } }
            send.close(); recv.close(); stream.close()
        }
    }

    private suspend fun proxy(peer: String, request: Request, send: SendStream) {
        val path = request.snapshotRevision?.let { "${request.path}?revision=$it" } ?: request.path
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(30)).header("Accept", if (request.path.substringBefore('?').endsWith("/events")) "text/event-stream" else "application/json")
        if (token.isNotBlank()) builder.header("X-Pilot-Token", token)
        if (request.method != "GET") {
            builder.header("Content-Type", "application/json")
            request.requestId?.let { builder.header("X-Pilot-Request-Id", "$peer:$it") }
        }
        builder.method(request.method, if (request.method == "GET") HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(request.body.ifBlank { "{}" }))
        val response = try { runInterruptible(Dispatchers.IO) { http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) } }
        catch (_: java.net.ConnectException) { reply(send, 503, "{\"error\":\"电脑上的 Pilot 未启动，请先启动 Pilot\"}"); return }
        val type = response.headers().firstValue("Content-Type").orElse("application/json")
        val encoding = if (request.acceptGzipFrames && (type.startsWith("application/json") || type.substringBefore(';').endsWith("+json"))) "gzip" else ""
        response.body().use { input ->
            Protocol.write(send, Protocol.json.encodeToString(ResponseHead.serializer(), ResponseHead(response.statusCode(), type, encoding)).toByteArray())
            var count = 0L
            val buffer = ByteArray(32 * 1024)
            while (currentCoroutineContext().isActive) {
                val size = withTimeout(35_000) { runInterruptible(Dispatchers.IO) { input.read(buffer) } }
                if (size < 0) break
                if (size == 0) continue
                count += size
                require(type.startsWith("text/event-stream") || count <= Protocol.MAX_RESPONSE) { "响应过大" }
                val chunk = buffer.copyOf(size)
                val encoded = if (encoding.isEmpty()) chunk else FrameCodec.encode(chunk)
                withTimeout(15_000) { Protocol.write(send, encoded) }
            }
            Protocol.write(send, byteArrayOf()); send.finish()
        }
    }

    private suspend fun reply(send: SendStream, code: Int, body: String) {
        Protocol.write(send, Protocol.json.encodeToString(ResponseHead.serializer(), ResponseHead(code)).toByteArray())
        Protocol.write(send, body.toByteArray()); Protocol.write(send, byteArrayOf()); send.finish()
    }
}
