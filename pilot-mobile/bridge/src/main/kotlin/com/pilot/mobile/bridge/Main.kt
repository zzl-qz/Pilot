package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.EndpointTicket
import kotlinx.coroutines.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

fun main(args: Array<String>) = runBlocking {
    // 仅作用于这个连接桥 JVM，用于读取系统代理线索；不改变系统代理，也不代表原生库支持 HTTP 代理。
    if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true")
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let {
        require(it + 1 < args.size) { "$name 缺少参数" }; args[it + 1]
    }
    fun configuredRelays() = option("--relay")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()
    if ("--check-runtime" in args) {
        // 无设备身份、管理口或外网连接的启动探针，退出码用于判断是否需要修复。
        val status = try {
            val check = createEndpoint(computer.iroh.SecretKey.generate().toBytes(), development = false)
            try {
                val matrix = com.google.zxing.MultiFormatWriter().encode("pilot-runtime-check", com.google.zxing.BarcodeFormat.QR_CODE, 64, 64)
                com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", java.io.ByteArrayOutputStream())
                0
            } finally { check.shutdown() }
        } catch (_: UnsatisfiedLinkError) { 12 }
        catch (error: NoClassDefFoundError) {
            var cause: Throwable? = error.cause
            var nativeFailure = false
            repeat(12) { if (cause is UnsatisfiedLinkError) nativeFailure = true; cause = cause?.cause }
            if (nativeFailure) 12 else 11
        }
        catch (_: Exception) { 11 }
        println(if (status == 0) "PILOT_MOBILE_RUNTIME_READY" else "PILOT_MOBILE_RUNTIME_UNAVAILABLE")
        kotlin.system.exitProcess(status)
    }
    if ("--probe-relay-server" in args) { relayProbeServer(configuredRelays()); return@runBlocking }
    if (args.firstOrNull() == "--probe-relay-client") { relayProbeClient(args[1], configuredRelays()); return@runBlocking }
    val port = (option("--pilot-port") ?: "9999").toInt()
    require(port in 1..65535) { "Pilot 端口无效" }
    val parent = option("--parent-pid")?.let { ProcessHandle.of(it.toLong()).orElseThrow { IllegalArgumentException("Pilot 后台已经退出") } }
    val store = PairingStore(Path.of(option("--data-dir") ?: ".pilot/mobile"))
    val relays = configuredRelays()
    require(relays.size <= 4) { "最多配置 4 个中继地址" }
    relays.forEach {
        val uri = runCatching { URI(it) }.getOrNull()
        require(uri?.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) { "中继必须使用不含认证信息的 HTTPS 地址" }
    }
    require("--no-relay" !in args || relays.isEmpty()) { "不能同时配置中继与 --no-relay" }
    val publicServices = "--no-relay" !in args && relays.isEmpty()
    val mode = if (relays.isNotEmpty()) "relay-enabled" else if (publicServices) "automatic" else "direct-only"
    val endpoint = createEndpoint(store.key, relays, publicServices)
    val bridge = Bridge(endpoint, store, port, System.getenv("PILOT_SECURITY_TOKEN") ?: "")
    val network = PairingNetwork(store, mode, ticket = {
        endpoint.addr().use { address ->
            EndpointTicket.fromAddr(address).use { ticket ->
                ticket.toString()
            }
        }
    }, online = if (publicServices || relays.isNotEmpty()) ({ endpoint.online() }) else null,
        diagnose = { diagnoseNetwork(URI(relays.firstOrNull() ?: "https://aps1-1.relay.n0.iroh.link").host) },
        observe = { observeEnvironment(URI(relays.firstOrNull() ?: "https://aps1-1.relay.n0.iroh.link"),
            inspectSystemProxy = mode != "direct-only" && System.getProperty("java.net.useSystemProxies") == "true") },
        relayAvailable = { endpoint.addr().use { it.relayUrl() != null } })
    val admin = PairingAdmin(store, bridge, relayConfigured = relays.isNotEmpty(), network = network::snapshot) {
        network.issue(option("--name") ?: "我的电脑", relays, publicServices)
    }
    val adminUrl = admin.start()
    println("PILOT_MOBILE_ADMIN $adminUrl")
    println("连接模式：$mode；二维码本机生成，网络探测在后台进行。")
    if ("--no-open" !in args && Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop().browse(URI(adminUrl)) }
    Runtime.getRuntime().addShutdownHook(Thread {
        admin.close(); network.close()
        runBlocking { withTimeoutOrNull(3000) { endpoint.shutdown() } }
    })
    // 后台被强制退出时也结束连接桥，避免 Windows 等系统遗留旧身份的进程。
    val parentWatch = parent?.let { process -> launch {
        while (process.isAlive) delay(1000)
        this@runBlocking.cancel("Pilot 后台已经退出")
    } }
    try { bridge.serve() } finally { parentWatch?.cancel(); admin.close(); network.close(); endpoint.shutdown() }
}
