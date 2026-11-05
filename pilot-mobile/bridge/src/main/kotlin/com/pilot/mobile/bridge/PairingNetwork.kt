package com.pilot.mobile.bridge

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 公网就绪探测在后台进行；HTTP 出码只读本机地址，不等待网络。 */
class PairingNetwork(
    private val store: PairingStore,
    private val mode: String,
    private val ticket: () -> String,
    private val online: (suspend () -> Unit)?,
    private val timeoutMillis: Long = 15_000,
    private val retryMillis: Long = 5_000,
    private val diagnose: suspend () -> String = { "" },
) : AutoCloseable {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var state = if (online == null) "local" else "preparing"
    @Volatile private var hint = ""

    init {
        if (online != null) worker.launch {
            while (isActive) {
                try {
                    withTimeout(timeoutMillis) { online.invoke() }
                    state = "ready"
                    break
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    state = "unavailable"
                } catch (_: Exception) {
                    state = "unavailable"
                }
                hint = runCatching { withTimeout(3_000) { diagnose() } }.getOrDefault("")
                delay(retryMillis)
            }
        }
    }

    fun issue(name: String, relays: List<String>, publicServices: Boolean) =
        store.issue(ticket(), name, relays, publicServices)

    fun snapshot(): JsonObject {
        store.refreshRouting(ticket())
        val current = state
        return buildJsonObject {
            put("connectionMode", mode)
            put("networkState", current)
            put("networkMessage", when (current) {
                "local" -> "仅尝试 P2P 直连，两端需有可互达地址。"
                "ready" -> "跨网服务已完成初始化，等待手机扫码；实际连接路径以手机显示为准。"
                "unavailable" -> "跨网服务暂未就绪，正在后台重试；不同网络请等待恢复后再扫码。" + hint
                else -> "正在后台准备跨网服务，二维码生成不等待此步骤；就绪后二维码会自动更新。"
            })
        }
    }

    override fun close() { worker.cancel() }
}
