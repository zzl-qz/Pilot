package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.EndpointTicket
import computer.iroh.SecretKey
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BridgeIntegrationTest {
    @Test fun `真实加密直连配对访问事件转发与设备撤销`() = runBlocking {
        val directory = Files.createTempDirectory("pilot-bridge")
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mutations = AtomicInteger()
        upstream.createContext("/") { e ->
            if (e.requestURI.path == "/api/sessions/ses_slow") Thread.sleep(700)
            val bytes = if (e.requestURI.path.endsWith("/events")) {
                e.responseHeaders.set("Content-Type", "text/event-stream")
                "data: {\"text\":\"中文进度\"}\n\n".toByteArray()
            } else {
                if (e.requestMethod == "POST") mutations.incrementAndGet()
                "{\"ok\":true}".toByteArray()
            }
            e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }; e.close()
        }
        upstream.start()
        val store = PairingStore(directory)
        val desktop = createEndpoint(store.key, development = false)
        val phone = createEndpoint(SecretKey.generate().toBytes(), development = false)
        val secondPhone = createEndpoint(SecretKey.generate().toBytes(), development = false)
        val secondClient = RemoteClient(secondPhone, EndpointTicket.fromAddr(desktop.addr()).toString())
        val bridge = Bridge(desktop, store, upstream.address.port)
        val serving = launch { bridge.serve() }
        val ticket = EndpointTicket.fromAddr(desktop.addr()).toString()
        val client = RemoteClient(phone, ticket)
        try {
            withTimeout(30_000) {
                assertEquals(401, client.request(Request()).first.status)
                val invite = store.issue(ticket, "电脑", emptyList(), false)
                assertEquals(200, client.request(Request("POST", "/pair", pairingSecret = invite.secret, deviceName = "测试手机")).first.status)
                assertEquals(401, client.request(Request("POST", "/pair", pairingSecret = invite.secret)).first.status)
                assertEquals(200, client.request(Request()).first.status)
                assertEquals(403, client.request(Request(path = "/api/settings")).first.status)
                assertEquals(200, client.request(Request("POST", "/api/sessions/ses_1/messages", "{\"content\":\"测试\"}")).first.status)
                assertEquals(1, mutations.get())
                // 模拟已授权手机在弱网中上传暂停超过 10 秒，不能按陌生设备的短超时截断。
                val slowConnection = phone.connect(desktop.addr(), Protocol.alpn)
                val slowStream = slowConnection.openBi()
                val slowSend = slowStream.send(); val slowRecv = slowStream.recv()
                try {
                    val bytes = Protocol.json.encodeToString(Request.serializer(), Request(path = "/api/health")).toByteArray()
                    slowSend.writeAll(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                    delay(11_000)
                    slowSend.writeAll(bytes); slowSend.finish()
                    assertEquals(200, Protocol.json.decodeFromString(ResponseHead.serializer(), String(Protocol.read(slowRecv))).status)
                    while (Protocol.read(slowRecv).isNotEmpty()) { /* 等待明确的响应结束帧 */ }
                } finally {
                    slowRecv.close(); slowSend.close(); slowStream.close()
                    slowConnection.close(0, byteArrayOf()); slowConnection.close()
                }
                val received = mutableListOf<String>()
                val decoder = SseDecoder { received += it.text("text") }
                client.transfer(Request(path = "/api/sessions/ses_1/events?after=0")) { decoder.feed(it) }
                assertEquals(listOf("中文进度"), received)
                assertTrue(client.pathLabel().startsWith("直连"), client.pathLabel())
                val impatient = RemoteClient(phone, ticket, requestTimeoutMillis = 150)
                try {
                    assertFailsWith<java.io.IOException> { impatient.request(Request(path = "/api/sessions/ses_slow")) }
                    assertTrue(currentCoroutineContext().isActive, "请求超时不能取消外层重连循环")
                } finally { impatient.disconnect() }
                val secondInvite = store.issue(ticket, "电脑", emptyList(), false)
                assertEquals(200, secondClient.request(Request("POST", "/pair", pairingSecret = secondInvite.secret, deviceName = "第二台手机")).first.status)
                val simultaneous = awaitAll(async { client.request(Request()).first.status }, async { secondClient.request(Request()).first.status })
                assertEquals(listOf(200, 200), simultaneous)
                assertTrue(bridge.online(phone.id().toString()))
                assertTrue(bridge.online(secondPhone.id().toString()))
                bridge.revoke(phone.id().toString())
                client.disconnect()
                assertEquals(401, client.request(Request()).first.status)
                assertEquals(200, secondClient.request(Request()).first.status, "撤销一台手机不能影响另一台")
            }
        } finally {
            client.disconnect(); secondClient.disconnect(); serving.cancelAndJoin(); desktop.shutdown(); phone.shutdown(); secondPhone.shutdown(); upstream.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
}
