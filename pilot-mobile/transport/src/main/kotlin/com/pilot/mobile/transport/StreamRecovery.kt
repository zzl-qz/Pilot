package com.pilot.mobile.transport

/** 正常 EOF 也需要退避；只有稳定连接才能重置，坏事件按确认游标限制重试。 */
class StreamRecovery {
    private var delay = 1000L
    private var failedCursor = -1L
    private var failures = 0
    fun disconnected(connectedMillis: Long): Long {
        if (connectedMillis >= 10_000) delay = 1000L
        val current = delay
        delay = (delay * 2).coerceAtMost(15_000)
        return current
    }
    fun failedConsumption(cursor: Long): Boolean {
        if (cursor != failedCursor) { failedCursor = cursor; failures = 0 }
        return ++failures <= 3
    }
}
