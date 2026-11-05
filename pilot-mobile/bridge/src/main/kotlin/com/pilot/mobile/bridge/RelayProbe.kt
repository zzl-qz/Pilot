package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.*
import kotlinx.coroutines.*

/** 显式执行的外网探针：仅固定文本。两端分处网络命名空间且只绑定回环，避免走直连。 */
private suspend fun probeEndpoint(relays: List<String>): Endpoint {
    require(relays.isNotEmpty()) { "中继探针需要显式提供已配置的中继地址，不会自动使用公共服务" }
    return Endpoint.bind(EndpointOptions(
    preset = presetMinimal(), bindAddr = "127.0.0.1:0", alpns = listOf(Protocol.alpn), relayMode = RelayMode.customFromUrls(relays),
    ))
}

suspend fun relayProbeServer(relays: List<String>) {
    val endpoint = probeEndpoint(relays)
    try {
        withTimeout(60_000) {
            endpoint.online()
            val address = EndpointAddr(endpoint.id(), endpoint.addr().relayUrl(), emptyList())
            println("PROBE_TICKET ${EndpointTicket.fromAddr(address)}")
            val connection = endpoint.acceptNext()!!.accept().connect()
            val stream = connection.acceptBi()
            val recv = stream.recv(); val send = stream.send()
            val message = Protocol.read(recv)
            check(String(message) == "pilot-relay-probe")
            Protocol.write(send, message); send.finish()
            connection.closed()
            recv.close(); send.close(); stream.close(); connection.close()
        }
    } finally { endpoint.shutdown() }
}

suspend fun relayProbeClient(ticket: String, relays: List<String>) {
    val endpoint = probeEndpoint(relays)
    try {
        withTimeout(45_000) {
            endpoint.online()
            val connection = endpoint.connect(EndpointTicket.fromString(ticket).endpointAddr(), Protocol.alpn)
            val stream = connection.openBi()
            val send = stream.send(); val recv = stream.recv()
            Protocol.write(send, "pilot-relay-probe".toByteArray()); send.finish()
            check(String(Protocol.read(recv)) == "pilot-relay-probe")
            val selected = connection.paths().firstOrNull { it.isSelected }
            check(selected?.isRelay == true) { "探针未走中继，不能作为回退通过证据" }
            println("PASS: 固定探针经公网中继往返，RTT ${selected.rttMs} ms")
            connection.close(0, byteArrayOf())
            recv.close(); send.close(); stream.close(); connection.close()
        }
    } finally { endpoint.shutdown() }
}
