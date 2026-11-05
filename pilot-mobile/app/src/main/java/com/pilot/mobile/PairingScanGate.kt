package com.pilot.mobile

import com.pilot.mobile.transport.PairingInvite

sealed interface PairingScanDecision {
    data class Valid(val code: String) : PairingScanDecision
    data class Invalid(val message: String) : PairingScanDecision
    data object Ignored : PairingScanDecision
}

/** 同一个有效码只交付一次；无关二维码留在扫码页，避免退出后再报错。 */
class PairingScanGate(private val now: () -> Long = System::currentTimeMillis) {
    private var completed = false
    private var lastInvalid: String? = null

    fun accept(raw: String): PairingScanDecision {
        if (completed) return PairingScanDecision.Ignored
        val code = raw.trim()
        return try {
            PairingInvite.decode(code, now())
            completed = true
            PairingScanDecision.Valid(code)
        } catch (error: IllegalArgumentException) {
            if (lastInvalid == code) return PairingScanDecision.Ignored
            lastInvalid = code.take(8193)
            PairingScanDecision.Invalid(error.message ?: "配对码无效，请在电脑上重新生成")
        } catch (_: Exception) {
            PairingScanDecision.Invalid("配对码无效，请在电脑上重新生成")
        }
    }
}
