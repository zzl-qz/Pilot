package com.pilot.mobile.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.Collections

/** 不按网卡名称或操作系统猜测路由；实际选路始终由系统与 iroh 完成。 */
internal data class NetworkEnvironment(
    val availability: String,
    val interfaceCount: Int = 0,
    val ipv4: Boolean = false,
    val ipv6: Boolean = false,
    val tunnelHint: Boolean = false,
    val proxyTypes: Set<String> = emptySet(),
    val proxyDetection: String = "configured-only",
    val invalidProxy: Boolean = false,
    val fingerprint: String = "",
) {
    fun payload(): JsonObject = buildJsonObject {
        put("availability", availability)
        put("interfaceCount", interfaceCount)
        put("ipv4", ipv4); put("ipv6", ipv6); put("tunnelHint", tunnelHint)
        put("proxyTypes", JsonArray(proxyTypes.sorted().map(::JsonPrimitive)))
        put("proxyDetection", proxyDetection); put("invalidProxy", invalidProxy)
        put("explicitProxySupported", false)
        put("message", when (availability) {
            "none" -> "未检测到可用 IP 网卡，请检查网线、Wi-Fi 或 VPN。"
            "unknown" -> "暂时无法读取系统网络状态，仍按实际连接结果判断。"
            else -> "有线、Wi-Fi 与多网卡由系统自动选路。" +
                (if (tunnelHint) " 检测到隧道或虚拟地址线索，TUN 按系统路由生效。" else "") +
                (if (proxyTypes.isNotEmpty()) " 检测到 HTTP/SOCKS 代理配置；原生连接不提供完整的 HTTP/SOCKS 代理隧道。若网络只允许代理出网，当前版本不支持该路径。" else "") +
                (if (invalidProxy) " 部分代理配置格式无效。" else "")
        })
    }

    companion object { fun unknown() = NetworkEnvironment("unknown") }
}

internal data class InterfaceObservation(val id: String, val addresses: List<ByteArray>, val tunnel: Boolean = false)
internal data class ProxyObservation(val types: Set<String>, val detection: String, val invalid: Boolean, val signature: List<String>)

internal fun environmentSnapshot(interfaces: List<InterfaceObservation>, proxy: ProxyObservation): NetworkEnvironment {
    val addresses = interfaces.flatMap { it.addresses }
    return NetworkEnvironment(
        availability = if (interfaces.isEmpty()) "none" else "available",
        interfaceCount = interfaces.size,
        ipv4 = addresses.any { it.size == 4 }, ipv6 = addresses.any { it.size == 16 },
        tunnelHint = interfaces.any { it.tunnel } || addresses.any(::fakeIp),
        proxyTypes = proxy.types, proxyDetection = proxy.detection, invalidProxy = proxy.invalid,
        // 仅内部比较；不向 HTTP、日志或二维码增加网卡地址与代理凭证。
        fingerprint = (interfaces.map { it.id + ":" + it.addresses.map { a -> a.joinToString(".") }.sorted() }.sorted() +
            proxy.signature.sorted()).joinToString("|"),
    )
}

internal suspend fun observeEnvironment(target: URI, inspectSystemProxy: Boolean): NetworkEnvironment = runInterruptible(Dispatchers.IO) {
    val interfaces = (NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()).mapNotNull { network ->
        runCatching {
            if (!network.isUp || network.isLoopback) return@runCatching null
            val addresses = Collections.list(network.inetAddresses).filterNot {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress
            }.map { it.address }
            if (addresses.isEmpty()) null else InterfaceObservation(network.name, addresses, network.isPointToPoint)
        }.getOrNull()
    }
    val selected = if (inspectSystemProxy) runCatching { ProxySelector.getDefault()?.select(target).orEmpty() }.getOrNull() else null
    val configured = listOf("http.proxyHost", "https.proxyHost", "socksProxyHost", "http.proxyPort", "https.proxyPort", "socksProxyPort").mapNotNull { key ->
        System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()
    environmentSnapshot(interfaces, observeProxies(System.getenv(), configured, selected))
}

/** 只记录类型与无认证信息的端点摘要，不连接代理、不改系统设置、不执行 PAC 脚本。 */
internal fun observeProxies(env: Map<String, String>, properties: Map<String, String>, selected: List<Proxy>?): ProxyObservation {
    val types = mutableSetOf<String>()
    val signature = mutableListOf<String>()
    var invalid = false
    for (key in listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy")) {
        val value = env[key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val uri = runCatching { URI(value) }.getOrNull()
        val type = when (uri?.scheme?.lowercase()) {
            "http", "https" -> "http"
            "socks", "socks4", "socks5", "socks5h" -> "socks"
            else -> null
        }
        if (type == null || uri?.host.isNullOrBlank() || uri!!.port !in -1..65535 || uri.port == 0) {
            invalid = true; continue
        }
        types += type; signature += "$type:${uri.host}:${uri.port}"
    }
    for ((key, value) in properties.filterKeys { it.endsWith("Host") }) {
        val type = if (key == "socksProxyHost") "socks" else "http"
        types += type; signature += "$type:$value:" + properties[key.removeSuffix("Host") + "Port"].orEmpty()
    }
    selected.orEmpty().filter { it.type() != Proxy.Type.DIRECT }.forEach {
        val type = if (it.type() == Proxy.Type.SOCKS) "socks" else "http"
        types += type; signature += "$type:${it.address()}"
    }
    // NO_PROXY 是排除规则，不证明连接库支持代理，也不能用于自动扩大直连权限。
    env["NO_PROXY"]?.let { signature += "bypass:$it" }
    env["no_proxy"]?.let { signature += "bypass:$it" }
    return ProxyObservation(types, if (selected == null) "configured-only" else "system-and-configured", invalid, signature)
}

internal fun fakeIp(address: ByteArray): Boolean =
    address.size == 4 && (address[0].toInt() and 255) == 198 && (address[1].toInt() and 254) == 18
