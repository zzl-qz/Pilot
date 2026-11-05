package com.pilot.mobile

import com.pilot.mobile.transport.PairingInvite
import org.junit.Assert.*
import org.junit.Test

class PairingScanGateTest {
    private val now = 1_800_000_000_000L
    private fun code(expiresAt: Long = now + 300_000) =
        PairingInvite(ticket = "a".repeat(100), secret = "b".repeat(43), name = "另一台 Windows 电脑", expiresAt = expiresAt).encode()

    @Test fun unrelatedAndExpiredCodesKeepScanningWithoutDeliveringCredentials() {
        val gate = PairingScanGate { now }
        assertTrue(gate.accept("https://example.com") is PairingScanDecision.Invalid)
        assertEquals(PairingScanDecision.Ignored, gate.accept("https://example.com"))
        assertTrue(gate.accept("x".repeat(10_000)) is PairingScanDecision.Invalid)
        val expired = gate.accept(code(now)) as PairingScanDecision.Invalid
        assertTrue(expired.message.contains("过期"))
        assertFalse(expired.message.contains("pilot://"))
        assertEquals(PairingScanDecision.Valid(code()), gate.accept(" \n" + code() + " \n"))
    }

    @Test fun successfulScanIsDeliveredOnlyOnceAcrossRepeatedFrames() {
        val gate = PairingScanGate { now }
        assertTrue(gate.accept(code()) is PairingScanDecision.Valid)
        repeat(20) { assertEquals(PairingScanDecision.Ignored, gate.accept(code())) }
    }

    @Test fun malformedAndIncompatibleCodesCanBeFollowedByAValidScan() {
        val gate = PairingScanGate { now }
        assertTrue(gate.accept("pilot://pair/broken") is PairingScanDecision.Invalid)
        val incompatible = PairingInvite(ticket = "a".repeat(100), secret = "b".repeat(43), name = "电脑", expiresAt = now + 300_000, version = 1).encode()
        assertTrue(gate.accept(incompatible) is PairingScanDecision.Invalid)
        assertTrue(gate.accept(code()) is PairingScanDecision.Valid)
    }
}
