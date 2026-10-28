package com.pilot.core.loop;

import com.pilot.core.plan.PlanItem;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionGateTest {
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    @Test
    void allowsFinishingWhenNothingChanged() {
        var gate = new CompletionGate(PROMPTS, true, "解释一下调用链");

        assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void asksForVerificationWhenCodeChangedWithoutPassingCheck() {
        var gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        var nudge = gate.evaluate(Set.of("src/A.java"), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.prompt().contains("src/A.java"), nudge.prompt());
        assertEquals(1, nudge.attempt());
    }

    @Test
    void documentationOnlyChangeDoesNotRequireBuild() {
        var gate = new CompletionGate(PROMPTS, true, "更新文档");

        // 第一道门（验证）跳过，但第二道门（自审）仍会问一次
        var nudge = gate.evaluate(Set.of("README.md"), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("自审"), nudge.reason());
    }

    @Test
    void passingCheckSkipsVerificationPrompt() {
        var gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        var nudge = gate.evaluate(Set.of("src/A.java"), List.of("mvnw test → exit 0"), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("自审"), nudge.reason());
    }

    @Test
    void exitZeroInsideCommandCannotHideItsRealFailureOrUnfinishedState() {
        for (String check : List.of(
                "node -e process.exit(1) exit 0 → exit 1",
                "node -e process.exit(1) exit 0 → RUNNING",
                "node -e process.exit(1) exit 0 → TIMED_OUT（进程退出码 0）")) {
            var gate = new CompletionGate(PROMPTS, true, "修一个 bug");
            var nudge = gate.evaluate(Set.of("src/A.java"), List.of(check), List.of(), List.of());
            assertNotNull(nudge, check);
            assertTrue(nudge.reason().contains("未验证"), check + ": " + nudge.reason());
        }
        var gate = new CompletionGate(PROMPTS, true, "修一个 bug");
        var passed = gate.evaluate(Set.of("src/A.java"),
                List.of("node -e process.exit(0) exit 1 → exit 0"), List.of(), List.of());
        assertNotNull(passed);
        assertTrue(passed.reason().contains("自审"), passed.reason());
    }

    @Test
    void verificationIsAskedAtMostTwiceThenFallsThroughToReview() {
        var gate = new CompletionGate(PROMPTS, true, "修一个 bug");
        Set<String> changed = Set.of("src/A.java");

        var first = gate.evaluate(changed, List.of(), List.of(), List.of());
        var second = gate.evaluate(changed, List.of(), List.of(), List.of());
        var third = gate.evaluate(changed, List.of(), List.of(), List.of());
        var fourth = gate.evaluate(changed, List.of(), List.of(), List.of());

        assertEquals(1, first.attempt());
        assertEquals(2, second.attempt());
        assertTrue(third.reason().contains("自审"));
        // 自审只做一次，之后放行
        assertNull(fourth);
    }

    @Test
    void readonlyResearchWithTooFewToolCallsGetsReviewed() {
        var gate = new CompletionGate(PROMPTS, true, "梳理一下请求调用链");
        gate.countToolCall();
        gate.countToolCall();

        var nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("自审"));
    }

    @Test
    void readonlyTaskThatIsNotResearchFinishesQuietly() {
        var gate = new CompletionGate(PROMPTS, true, "你好");
        gate.countToolCall();
        gate.countToolCall();

        assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void recordsInspectedPathsForReadonlyReview() {
        var gate = new CompletionGate(PROMPTS, true, "分析这个模块");
        gate.countToolCall();
        gate.countToolCall();
        gate.recordTarget(new ToolCall("call_1", "read_file", Map.of("path", "src/A.java")));
        gate.recordTarget(new ToolCall("call_2", "search_text", Map.of("query", "A")));

        var nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.prompt().contains("src/A.java"), nudge.prompt());
    }

    @Test
    void disabledGateNeverNudges() {
        var gate = new CompletionGate(PROMPTS, false, "修一个 bug");
        gate.countToolCall();
        gate.countToolCall();

        assertNull(gate.evaluate(Set.of("src/A.java"), List.of(), List.of(), List.of()));
    }

    @Test
    void planProgressAppearsInReviewPrompt() {
        var gate = new CompletionGate(PROMPTS, true, "做三件事");
        var plan = List.of(new PlanItem("第一步", PlanItem.COMPLETED), new PlanItem("第二步", PlanItem.PENDING));

        var nudge = gate.evaluate(Set.of("src/A.java"), List.of("mvnw test → exit 0"), plan, List.of("第二步"));

        assertNotNull(nudge);
        assertTrue(nudge.prompt().contains("1/2"), nudge.prompt());
        assertTrue(nudge.prompt().contains("第二步"), nudge.prompt());
    }
}
