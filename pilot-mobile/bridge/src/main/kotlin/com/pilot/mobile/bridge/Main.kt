package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.EndpointTicket
import kotlinx.coroutines.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

fun main(args: Array<String>) = runBlocking {
    if ("--probe-relay-server" in args) { relayProbeServer(); return@runBlocking }
    if (args.firstOrNull() == "--probe-relay-client") { relayProbeClient(args[1]); return@runBlocking }
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let {
        require(it + 1 < args.size) { "$name 缺少参数" }; args[it + 1]
    }
    val port = (option("--pilot-port") ?: "9999").toInt()
    require(port in 1..65535) { "Pilot 端口无效" }
    val parent = option("--parent-pid")?.let { ProcessHandle.of(it.toLong()).orElseThrow { IllegalArgumentException("Pilot 后台已经退出") } }
    val store = PairingStore(Path.of(option("--data-dir") ?: ".pilot/mobile"))
    val relays = option("--relay")?.split(',') ?: emptyList()
    relays.forEach { require(URI(it).scheme == "https" && URI(it).host != null) { "中继必须使用 HTTPS" } }
    val development = "--no-relay" !in args && relays.isEmpty()
    val endpoint = createEndpoint(store.key, relays, development)
    val bridge = Bridge(endpoint, store, port, System.getenv("PILOT_SECURITY_TOKEN") ?: "")
    val admin = PairingAdmin(store, bridge) {
        if (development || relays.isNotEmpty()) withTimeout(20_000) { endpoint.online() }
        store.issue(EndpointTicket.fromAddr(endpoint.addr()).toString(), option("--name") ?: "我的电脑", relays, development)
    }
    val adminUrl = admin.start()
    println("PILOT_MOBILE_ADMIN $adminUrl")
    if (development) println("当前使用开发中继；正式发布需配置托管或自建中继。")
    if ("--no-open" !in args && Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop().browse(URI(adminUrl)) }
    Runtime.getRuntime().addShutdownHook(Thread {
        admin.close()
        runBlocking { withTimeoutOrNull(3000) { endpoint.shutdown() } }
    })
    // 后台被强制退出时也结束连接桥，避免 Windows 等系统遗留旧身份的进程。
    val parentWatch = parent?.let { process -> launch {
        while (process.isAlive) delay(1000)
        this@runBlocking.cancel("Pilot 后台已经退出")
    } }
    try { bridge.serve() } finally { parentWatch?.cancel(); admin.close(); endpoint.shutdown() }
}
