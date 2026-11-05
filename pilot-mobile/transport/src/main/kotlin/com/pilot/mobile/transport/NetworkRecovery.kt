package com.pilot.mobile.transport

import kotlinx.coroutines.*

/** 合并网络抖动；恢复动作只能重建读取链路，不重放提交。繁忙的修改请求先完成或明确失败。 */
class NetworkRecovery(
    parent: CoroutineScope,
    private val busy: () -> Boolean,
    private val offline: () -> Unit,
    private val recover: suspend () -> Boolean,
    private val failure: (Exception) -> Unit = {},
    private val debounceMillis: Long = 500,
) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var previous: String? = null
    private var pending: Job? = null

    fun changed(fingerprint: String, available: Boolean) {
        if (previous == fingerprint) return
        val first = previous == null
        previous = fingerprint
        pending?.cancel()
        if (!available) { offline(); return }
        if (first) return
        pending = scope.launch {
            delay(debounceMillis)
            while (isActive) {
                try { if (!busy() && recover()) break }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { failure(error); delay(1_000) }
                delay(100)
            }
        }
    }

    override fun close() { scope.cancel() }
}
