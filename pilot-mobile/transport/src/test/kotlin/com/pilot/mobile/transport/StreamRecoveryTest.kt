package com.pilot.mobile.transport

import kotlin.test.*
class StreamRecoveryTest {
    @Test fun `瞬断和正常 EOF 都积累退避且稳定连接后恢复初值`() {
        val policy = StreamRecovery()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), (1..6).map { policy.disconnected(50) })
        assertEquals(1000L, policy.disconnected(10000))
        assertEquals(2000L, policy.disconnected(50))
    }
    @Test fun `同一确认位置坏事件有限重试且推进游标后重新计数`() {
        val policy = StreamRecovery()
        repeat(3) { assertTrue(policy.failedConsumption(5)) }
        assertFalse(policy.failedConsumption(5))
        assertTrue(policy.failedConsumption(6))
    }
}
