package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.nio.file.Files

private suspend fun loopbackEndpoint(key: ByteArray) = Endpoint.bind(EndpointOptions(
    preset = presetN0(), bindAddr = "127.0.0.1:0", secretKey = key,
    alpns = listOf(Protocol.alpn), relayMode = RelayMode.defaultMode(),
))

/** 主机与容器的回环不互通，断网前后分别断言实际公网中继数据往返。 */
fun main(args: Array<String>) = runBlocking {
    if (args.firstOrNull() == "client") {
        val phone = loopbackEndpoint(SecretKey.generate().toBytes())
        try { withTimeout(40_000) {
            val connection = EndpointTicket.fromString(args[1]).use { parsed -> parsed.endpointAddr().use { phone.connect(it, Protocol.alpn) } }
            val stream = connection.openBi(); val send = stream.send(); val recv = stream.recv()
            try {
                Protocol.write(send, "pilot-network-recovery-probe".toByteArray()); send.finish()
                check(String(Protocol.read(recv)) == "pilot-network-recovery-probe")
                check(connection.paths().any { it.isSelected && it.isRelay })
                connection.close(0, byteArrayOf())
            } finally { recv.close(); send.close(); stream.close(); connection.close() }
        } } finally { phone.shutdown() }
        return@runBlocking
    }
    val directory = Files.createTempDirectory("pilot-network-health")
    val store = PairingStore(directory)
    val endpoint = loopbackEndpoint(store.key)
    val network = PairingNetwork(store, "automatic", {
        endpoint.addr().use { address -> EndpointTicket.fromAddr(address).use { it.toString() } }
    }, online = { endpoint.online() },
        observe = { observeEnvironment(URI("https://aps1-1.relay.n0.iroh.link"), inspectSystemProxy = false) },
        relayAvailable = { endpoint.addr().use { it.relayUrl() != null } }, pollMillis = 100, timeoutMillis = 500)
    val serving = launch {
        while (isActive) {
            val incoming = endpoint.acceptNext() ?: break
            launch {
                val connection = incoming.accept().connect()
                val stream = connection.acceptBi()
                val recv = stream.recv(); val send = stream.send()
                try { Protocol.write(send, Protocol.read(recv)); send.finish(); connection.closed() }
                finally { recv.close(); send.close(); stream.close(); connection.close() }
            }
        }
    }
    suspend fun waitFor(expected: String) {
        try { withTimeout(45_000) { while (network.snapshot().text("networkState") != expected) delay(100) } }
        catch (error: TimeoutCancellationException) {
            println("FAIL: 等待 $expected；实际 ${network.snapshot().text("networkState")}"); throw error
        }
    }
    try {
        waitFor("ready")
        val oldTicket = endpoint.addr().use { address -> address.id().use { id ->
            EndpointAddr(id, address.relayUrl(), emptyList()).use { relayOnly -> EndpointTicket.fromAddr(relayOnly).use { it.toString() } }
        } }
        val invite = network.issue("隔离测试端点", emptyList(), true)
        println("PILOT_NETWORK_STAGE_READY $oldTicket")
        waitFor("offline")
        println("PILOT_NETWORK_STAGE_OFFLINE")
        waitFor("ready")
        println("PILOT_NETWORK_STAGE_RESTORED")
        check(withContext(Dispatchers.IO) { System.`in`.bufferedReader().readLine() } == "verified")
        check(store.currentInvite()?.secret == invite.secret && store.currentInvite()?.expiresAt == invite.expiresAt)
        val environment = network.snapshot()["networkEnvironment"]!!.jsonObject
        println("PASS: 实际断网与恢复、旧票据经公网中继往返、授权有效期保留；检测到代理类型 ${environment["proxyTypes"]!!.jsonArray}")
    } finally { network.close(); serving.cancelAndJoin(); endpoint.shutdown(); directory.toFile().deleteRecursively() }
}
