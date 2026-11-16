package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 手机连电脑的延迟测量。
 *
 * <p>产品上说「优先 P2P、延迟更低」，这句话要么有数字，要么就是广告。
 * 这里把它做成可以反复跑、结果可以直接贴进验证记录的测量：同一台电脑分别以
 * 「同局域网直连」「跨网直连」「只能走中继」三种方式各测一轮，
 * 每次记录连接建立耗时、首个字节耗时与链路 RTT 的分位数。
 *
 * <p>测量不产生业务副作用：只发固定探针文本，对端原样回显。
 */

/** 单个样本：建连、首字节、链路 RTT 三段耗时，单位毫秒。 */
data class Sample(val connectMs: Long, val firstByteMs: Long, val rttMs: Long?, val relay: Boolean) {
    fun toJson() = buildJsonObject {
        put("connectMs", connectMs)
        put("firstByteMs", firstByteMs)
        rttMs?.let { put("rttMs", it) }
        put("path", if (relay) "relay" else "direct")
    }
}

/**
 * 最近秩法分位数。
 *
 * <p>样本少时不做插值：P95 取第 ceil(0.95n) 小的值。宁可保守取大值，
 * 也不要用插值把尾部抹平——延迟问题几乎总在尾部。
 */
fun percentile(values: List<Long>, fraction: Double): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = kotlin.math.ceil(fraction.coerceIn(0.0, 1.0) * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

/** 一批样本的汇总；某项没有观测（例如链路不报 RTT）时给 null，不填 0。 */
data class Measurement(val label: String, val samples: List<Sample>) {
    private fun values(pick: (Sample) -> Long?): List<Long> = samples.mapNotNull(pick)

    /** 连接实际走的路径；同一批里混用两类路径时标记 mixed，避免把两种条件混成一组结论。 */
    fun path(): String {
        val kinds = samples.map { it.relay }.distinct()
        return when {
            kinds.isEmpty() -> "unknown"
            kinds.size > 1 -> "mixed"
            kinds.first() -> "relay"
            else -> "direct"
        }
    }

    fun summary() = buildJsonObject {
        put("label", label)
        put("path", path())
        put("samples", samples.size)
        putJsonObject("connectMs") {
            put("p50", percentile(values { it.connectMs }, 0.50))
            put("p95", percentile(values { it.connectMs }, 0.95))
        }
        putJsonObject("firstByteMs") {
            put("p50", percentile(values { it.firstByteMs }, 0.50))
            put("p95", percentile(values { it.firstByteMs }, 0.95))
        }
        putJsonObject("rttMs") {
            put("p50", percentile(values { it.rttMs }, 0.50))
            put("p95", percentile(values { it.rttMs }, 0.95))
            put("observed", values { it.rttMs }.size)
        }
        putJsonArray("details") { samples.forEach { add(it.toJson()) } }
    }
}

/**
 * 对同一个目标连续测量 [samples] 次。
 *
 * <p>每次都重新建连：复用一条长连接只能测到往返，而用户真正的等待往往发生在第一次连接。
 */
suspend fun measure(target: String, relays: List<String>, samples: Int, label: String): Measurement {
    require(samples in 1..200) { "样本数需要在 1..200 之间" }
    val endpoint = createEndpoint(SecretKey.generate().toBytes(), relays, relays.isEmpty())
    val collected = mutableListOf<Sample>()
    try {
        endpoint.online()
        repeat(samples) {
            val started = System.nanoTime()
            val connection = endpoint.connect(EndpointTicket.fromString(target).endpointAddr(), Protocol.alpn)
            val connected = System.nanoTime()
            val stream = connection.openBi()
            val send = stream.send()
            val recv = stream.recv()
            Protocol.write(send, "pilot-latency-probe".toByteArray())
            send.finish()
            Protocol.read(recv)
            val received = System.nanoTime()
            val path = connection.paths().firstOrNull { it.isSelected }
            collected += Sample(
                connectMs = (connected - started) / 1_000_000,
                firstByteMs = (received - connected) / 1_000_000,
                rttMs = path?.rttMs?.toLong(),
                relay = path?.isRelay == true,
            )
            recv.close(); send.close(); stream.close(); connection.close(0, byteArrayOf())
            delay(200)
        }
    } finally {
        endpoint.shutdown()
    }
    return Measurement(label, collected)
}

/** 打印一份可直接贴进验证记录的 JSON。 */
fun Measurement.printJson() {
    println(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary()))
}