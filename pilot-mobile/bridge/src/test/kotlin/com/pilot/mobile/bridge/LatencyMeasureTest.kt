package com.pilot.mobile.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 分位数与路径判定：这两件事决定报告里的数字代表什么，必须先钉住。 */
class LatencyMeasureTest {

    @Test
    fun percentileUsesNearestRankSoTheTailIsNotSmoothedAway() {
        val values = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)
        assertEquals(10L, percentile(values, 0.10), "最小样本")
        assertEquals(50L, percentile(values, 0.50), "中位数")
        // 10 个样本的 P95 是第 10 小（最大值），不是插值出来的 95
        assertEquals(100L, percentile(values, 0.95))
        assertEquals(100L, percentile(values, 1.0))
    }

    @Test
    fun percentileOfTinySamplesIsConservative() {
        // 样本很少时更要保守：3 个样本的 P95 直接取最大值
        assertEquals(300L, percentile(listOf(100L, 200L, 300L), 0.95))
        assertEquals(100L, percentile(listOf(100L), 0.95))
    }

    @Test
    fun emptyInputHasNoPercentileInsteadOfZero() {
        // 没有观测时给 null，不填 0：0ms 会被当成「极快」读进报告
        assertNull(percentile(emptyList(), 0.5))
    }

    @Test
    fun pathIsReportedPerSampleAndMixedBatchesAreCalledOut() {
        val direct = Measurement("同局域网", listOf(Sample(30, 12, 8, relay = false)))
        assertEquals("direct", direct.path())

        val relay = Measurement("回退中继", listOf(Sample(320, 90, 60, relay = true)))
        assertEquals("relay", relay.path())

        // 一轮里既有直连又回落到中继：不能当成同一条件，否则数字没有比较意义
        val mixed = Measurement("跨网", listOf(
            Sample(30, 12, 8, relay = false),
            Sample(300, 90, 60, relay = true),
        ))
        assertEquals("mixed", mixed.path())

        assertEquals("unknown", Measurement("空", emptyList()).path())
    }

    @Test
    fun missingRttIsCountedAsUnobservedRatherThanZero() {
        val measurement = Measurement("链路不报 RTT", listOf(
            Sample(30, 12, null, relay = false),
            Sample(32, 14, 9, relay = false),
        ))
        val summary = measurement.summary()
        val rtt = summary["rttMs"]!!.toString()
        assertEquals(true, rtt.contains("\"observed\":1"), "只有一条样本报了 RTT：$rtt")
        assertEquals(2, summary["samples"].toString().toInt())
    }
}