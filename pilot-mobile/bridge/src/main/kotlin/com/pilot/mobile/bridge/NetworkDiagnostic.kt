package com.pilot.mobile.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress

/** 只读诊断；不修改系统 DNS、路由、代理，也不向界面暴露电脑地址。 */
internal suspend fun diagnoseNetwork(host: String): String = runInterruptible(Dispatchers.IO) {
    runCatching { dnsHint(InetAddress.getAllByName(host).map { it.address }) }
        .getOrDefault(" 域名解析也未成功，请检查电脑 DNS、代理或防火墙。")
}

internal fun dnsHint(addresses: List<ByteArray>): String =
    if (addresses.any(::fakeIp))
        " 检测到代理/TUN 常用的虚拟 DNS 地址；请检查连接服务域名的真实 DNS 解析和 UDP/HTTPS 放行规则。"
    else " 请检查电脑网络、代理/TUN 或防火墙是否允许连接服务。"
