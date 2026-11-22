package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pilot.core.lint.LintGate;
import com.pilot.core.message.Message;

class CompletionBaselineTest {
    @Test
    void unchangedEarlierFailureIsNotDemandedAgain() {
        CompletionGate gate = new CompletionGate(true, "修复登录", LintGate.disabled());
        gate.baseline(List.of("mvn test → exit 1"));

        CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of("mvn test → exit 1"), List.of(),
            List.of(), false, Map.of(), null, null, "做完了");

        assertTrue(nudge == null || !nudge.reason().contains("未验证"), String.valueOf(nudge));
    }

    @Test
    void nearFullContextSkipsAnotherNudge() {
        assertFalse(CompletionPressure.tooFull(List.of(Message.user("短")), List.of(), 2_000_000));
    }
}
