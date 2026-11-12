package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pilot.core.plan.PlanItem;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.ToolCall;

class CompletionGateTest {
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    @Test
    void allowsFinishingWhenNothingChanged() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "解释一下调用链");

        assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void actionGoalWithoutAnyChangeIsPushedOnceToActuallyAct() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修复登录接口的空指针");

        CompletionGate.Nudge nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("动手"), nudge.reason());
        assertTrue(nudge.prompt().contains("修复登录接口的空指针"), nudge.prompt());
        // 只推一次，之后放行，避免对确实只需要回答的请求反复催办
        assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()));
    }

    /** 征求意见的目标不该被当成「该动手却没动手」：Codex 也不会要求把简历改成文件。 */
    @Test
    void consultationGoalWithActionWordIsNotPushedToAct() {
        for (String goal : List.of("看看怎么优化", "给我一些简历优化建议", "评估这份简历的优化空间")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, goal);

            assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()), goal);
        }
    }

    @Test
    void codeGoalWithActionWordStillRequiresAction() {
        for (String goal : List.of("优化首页加载性能", "修复登录接口的空指针")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, goal);

            CompletionGate.Nudge nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());

            assertNotNull(nudge, goal);
            assertTrue(nudge.reason().contains("动手"), goal);
        }
    }

    @Test
    void questionGoalMentioningActionWordsIsNotPushedToAct() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "为什么这里没有修复");

        assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void asksForVerificationWhenCodeChangedWithoutPassingCheck() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.prompt().contains("src/A.java"), nudge.prompt());
        assertEquals(1, nudge.attempt());
    }

    @Test
    void documentationOnlyChangeDoesNotRequireBuild() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "更新文档");

        // 第一道门（验证）跳过，但第二道门（自审）仍会问一次
        CompletionGate.Nudge nudge = gate.evaluate(Set.of("README.md"), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("自审"), nudge.reason());
    }

    @Test
    void passingCheckSkipsVerificationPrompt() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        CompletionGate.Nudge nudge =
            gate.evaluate(Set.of("src/A.java"), List.of("mvnw test → exit 0"), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("自审"), nudge.reason());
    }

    @Test
    void independentReviewerAvoidsDuplicateSelfReview() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        assertNull(gate.evaluate(Set.of("src/A.java"), List.of("mvnw test → exit 0"), List.of(), List.of(), true));
    }

    @Test
    void independentReviewerStillRequiresCodeVerification() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of(), List.of(), List.of(), true);
        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("未验证"), nudge.reason());
    }

    @Test
    void unrelatedSuccessfulCommandsDoNotVerifyCodeChanges() {
        for (String check : List.of("git status --short → exit 0", "git diff → exit 0", "node --version → exit 0",
            "java -version → exit 0", "npm --version → exit 0", "cargo metadata → exit 0")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

            CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of(check), List.of(), List.of());

            assertNotNull(nudge, check);
            assertTrue(nudge.reason().contains("未验证"), check + ": " + nudge.reason());
        }
    }

    @Test
    void pipedTestNeedsUnmaskedExitCodeBeforeItCanVerifyChanges() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "实现 CSV 解析并验证");
        Set<String> changed = Set.of("src/csv.js");
        CompletionGate.Nudge nudge = gate.evaluate(changed,
            List.of("node --test | tail -12 → exit 0"), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("未验证"));
        assertTrue(nudge.prompt().contains("管道"), nudge.prompt());
        assertTrue(nudge.prompt().contains("command_logs"), nudge.prompt());
        assertTrue(nudge.prompt().contains("node --test"), nudge.prompt());

        CompletionGate.Nudge verified = gate.evaluate(changed,
            List.of("node --test | tail -12 → exit 0", "node --test → exit 0"), List.of(), List.of());
        assertNotNull(verified);
        assertTrue(verified.reason().contains("自审"));
    }

    @Test
    void successfulBuildTestLintAndFormatChecksVerifyCodeChanges() {
        for (String check : List.of("mvnw -q test → exit 0", "gradlew :app:build → exit 0", "npm run lint → exit 0",
            "node --test → exit 0", "python3 -m pytest -q → exit 0", "cargo check → exit 0", "go vet ./... → exit 0",
            "git diff --check → exit 0")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

            CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of(check), List.of(), List.of());

            assertNotNull(nudge, check);
            assertTrue(nudge.reason().contains("自审"), check + ": " + nudge.reason());
        }
    }

    @Test
    void exitZeroInsideCommandCannotHideItsRealFailureOrUnfinishedState() {
        for (String check : List.of("node -e process.exit(1) exit 0 → exit 1",
            "node -e process.exit(1) exit 0 → RUNNING", "node -e process.exit(1) exit 0 → TIMED_OUT（进程退出码 0）")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");
            CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of(check), List.of(), List.of());
            assertNotNull(nudge, check);
            assertTrue(nudge.reason().contains("未验证"), check + ": " + nudge.reason());
        }
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");
        CompletionGate.Nudge passed =
            gate.evaluate(Set.of("src/A.java"), List.of("mvn -Dlabel=exit_1 test → exit 0"), List.of(), List.of());
        assertNotNull(passed);
        assertTrue(passed.reason().contains("自审"), passed.reason());
    }

    @Test
    void verificationIsAskedAtMostTwiceThenFallsThroughToReview() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");
        Set<String> changed = Set.of("src/A.java");

        CompletionGate.Nudge first = gate.evaluate(changed, List.of(), List.of(), List.of());
        CompletionGate.Nudge second = gate.evaluate(changed, List.of(), List.of(), List.of());
        CompletionGate.Nudge third = gate.evaluate(changed, List.of(), List.of(), List.of());
        CompletionGate.Nudge fourth = gate.evaluate(changed, List.of(), List.of(), List.of());

        assertEquals(1, first.attempt());
        assertEquals(2, second.attempt());
        assertTrue(third.reason().contains("自审"));
        // 自审只做一次，之后放行
        assertNull(fourth);
    }

    @Test
    void readonlyResearchWithTooFewToolCallsGetsReviewed() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "梳理一下请求调用链");
        gate.countToolCall();
        gate.countToolCall();

        CompletionGate.Nudge nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("自审"));
    }

    @Test
    void readonlyTaskThatIsNotResearchFinishesQuietly() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "你好");
        gate.countToolCall();
        gate.countToolCall();

        assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void recordsInspectedPathsForReadonlyReview() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "分析这个模块");
        gate.countToolCall();
        gate.countToolCall();
        gate.recordTarget(new ToolCall("call_1", "read_file", Map.of("path", "src/A.java")));
        gate.recordTarget(new ToolCall("call_2", "search_text", Map.of("query", "A")));

        CompletionGate.Nudge nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.prompt().contains("src/A.java"), nudge.prompt());
    }

    @Test
    void restoresInspectedPathsAndToolCountFromPersistedHistory() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "分析这个模块的调用链");
        List<Message> history = List.of(Message.user("分析这个模块的调用链"),
            Message.assistant("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "src/A.java")))),
            Message.tool("call_1", "class A {}"),
            Message.assistant("", List.of(new ToolCall("call_2", "read_file", Map.of("path", "src/B.java")))),
            Message.tool("call_2", "class B {}"),
            Message.assistant("", List.of(new ToolCall("call_3", "read_file", Map.of("path", "src/Skipped.java")))),
            Message.tool("call_3", "SKIPPED: 用户已纠正当前任务"));

        gate.restoreFrom(history);
        CompletionGate.Nudge nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.prompt().contains("src/A.java"), nudge.prompt());
        assertTrue(nudge.prompt().contains("src/B.java"), nudge.prompt());
        assertTrue(!nudge.prompt().contains("Skipped.java"), nudge.prompt());
    }

    @Test
    void disabledGateNeverNudges() {
        CompletionGate gate = new CompletionGate(PROMPTS, false, "修一个 bug");
        gate.countToolCall();
        gate.countToolCall();

        assertNull(gate.evaluate(Set.of("src/A.java"), List.of(), List.of(), List.of()));
    }

    @Test
    void planProgressAppearsInReviewPrompt() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "做三件事");
        List<PlanItem> plan = List.of(new PlanItem("第一步", PlanItem.COMPLETED), new PlanItem("第二步", PlanItem.PENDING));

        CompletionGate.Nudge nudge =
            gate.evaluate(Set.of("src/A.java"), List.of("mvnw test → exit 0"), plan, List.of("第二步"));

        assertNotNull(nudge);
        assertTrue(nudge.prompt().contains("1/2"), nudge.prompt());
        assertTrue(nudge.prompt().contains("第二步"), nudge.prompt());
    }
}
