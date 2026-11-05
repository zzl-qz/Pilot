package com.pilot.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/** Android 默认网络回调兼容 Wi-Fi、蜂窝、有线与 VPN，不依赖固定接口名或 IP。 */
internal class MobileNetworkMonitor(context: Context, private val changed: (String, Boolean) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private val sample = Runnable {
        if (!closed) runCatching {
            val network = manager?.activeNetwork
            val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
            val link = network?.let { manager?.getLinkProperties(it) }
            val transports = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN)
                .filter { capabilities?.hasTransport(it) == true }
            // 内部比较，绝不打印或展示地址、DNS、代理地址或认证信息。
            val fingerprint = if (network == null) "offline" else listOf(network.networkHandle, transports,
                link?.linkAddresses?.map { it.toString() }?.sorted(), link?.dnsServers?.map { it.hostAddress }?.sorted(),
                link?.httpProxy?.host, link?.httpProxy?.port, link?.httpProxy?.pacFileUrl).joinToString("|")
            changed(fingerprint, network != null)
        }
    }
    private fun schedule() { handler.removeCallbacks(sample); handler.postDelayed(sample, 200) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = schedule()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = schedule()
    }
    private val registered = runCatching { manager?.registerDefaultNetworkCallback(callback); manager != null }.getOrDefault(false)
    init { handler.post(sample) }
    override fun close() {
        closed = true; handler.removeCallbacks(sample)
        if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
