package com.pilot.mobile.bridge

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 公网就绪探测在后台进行；HTTP 出码只读本机地址，不等待网络。 */
internal class PairingNetwork(
    private val store: PairingStore,
    private val mode: String,
    private val ticket: () -> String,
    private val online: (suspend () -> Unit)?,
    private val timeoutMillis: Long = 15_000,
    private val retryMillis: Long = 5_000,
    private val diagnose: suspend () -> String = { "" },
    private val observe: (suspend () -> NetworkEnvironment)? = null,
    private val relayAvailable: (() -> Boolean)? = null,
    private val pollMillis: Long = 2_000,
) : AutoCloseable {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var state = if (mode == "direct-only") "local" else "preparing"
    @Volatile private var hint = ""
    @Volatile private var environment: NetworkEnvironment? = if (observe == null) null else NetworkEnvironment.unknown()
    @Volatile private var checkedAt = 0L
    @Volatile private var changes = 0L

    init {
        // 系统代理查询可能慢，独立采样，不能拖住出码或原生连接准备。
        observe?.let { collect -> worker.launch {
            while (isActive) {
                val next = try { withTimeoutOrNull(3_000) { collect() } ?: NetworkEnvironment.unknown() }
                catch (error: CancellationException) { currentCoroutineContext().ensureActive(); NetworkEnvironment.unknown() }
                catch (_: Exception) { NetworkEnvironment.unknown() }
                val previous = environment
                if (next.availability != "unknown" && previous != null &&
                    previous.availability != "unknown" && next.fingerprint != previous.fingerprint) changes++
                environment = next
                if (next.availability == "none") state = "offline"
                else if (previous?.availability == "none") state = if (mode == "direct-only") "local" else "preparing"
                delay(pollMillis)
            }
        } }
        if (online != null || observe != null || relayAvailable != null) worker.launch {
            var initialized = false
            while (isActive) {
                try {
                    state = when {
                        environment?.availability == "none" -> "offline"
                        mode == "direct-only" -> "local"
                        online == null -> if (relayAvailable?.invoke() == true) "ready" else "local"
                        else -> {
                            // FFI 的地址/回调可能仅包含已选中的中继 URL，不能当作已握手。
                            // online() 每次读取当前连接状态，结合地址检查，失败时不能沿用历史 ready。
                            withTimeout(if (initialized) 2_000 else timeoutMillis) { online.invoke() }
                            initialized = true
                            if (relayAvailable?.invoke() != false) "ready" else "unavailable"
                        }
                    }
                    hint = ""
                    if (state == "unavailable") hint = withTimeoutOrNull(3_000) { diagnose() }.orEmpty()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    state = if (environment?.availability == "none") "offline" else "unavailable"
                } catch (_: Exception) {
                    state = if (environment?.availability == "none") "offline" else "unavailable"
                }
                checkedAt = System.currentTimeMillis()
                if (state == "unavailable" && hint.isEmpty())
                    hint = runCatching { withTimeout(3_000) { diagnose() } }.getOrDefault("")
                delay(if (state == "unavailable" && !initialized) retryMillis else pollMillis)
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
            put("networkCheckedAt", checkedAt)
            put("networkChangeCount", changes)
            environment?.let { put("networkEnvironment", it.payload()) }
            put("networkMessage", when (current) {
                "local" -> "仅尝试 P2P 直连，两端需有可互达地址。"
                "ready" -> "最近一次跨网服务检查通过，实际直连或中继路径以手机显示为准。"
                "offline" -> "未检测到可用网络，请检查网线、Wi-Fi 或 VPN；本机仍可生成二维码，恢复网络后自动重试。"
                "unavailable" -> "跨网服务暂未就绪，正在后台重试；不同网络请等待恢复后再扫码。" + hint
                else -> "正在后台准备跨网服务，二维码生成不等待此步骤；就绪后二维码会自动更新。"
            })
        }
    }

    override fun close() { worker.cancel() }
}
