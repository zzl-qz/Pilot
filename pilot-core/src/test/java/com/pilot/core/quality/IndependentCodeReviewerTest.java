package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.ToolCall;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;

class IndependentCodeReviewerTest {
    @TempDir
    Path workspace;

    @Test
    void skipsSmallSingleFileEditsToAvoidUnnecessaryCalls() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            calls.incrementAndGet();
            return new ModelResponse("unexpected", List.of());
        };
        IndependentCodeReviewer reviewer = new IndependentCodeReviewer(model, workspace, () -> false);
        Map<String, TextChange> small =
            Map.of("src/main/A.java", new TextChange("class A {}\n", "class A { int x = 1; }\n"));

        assertFalse(IndependentCodeReviewer.shouldReview(small));
        assertNull(reviewer.review("微调字段", small));
        assertEqualsZero(calls.get());
    }

    @Test
    void returnsActionableGuidanceOnRefactorVerdictAndFailsOpenOnPassOrErrors() {
        Map<String, TextChange> multiFile =
            Map.of("src/main/OrderService.java", new TextChange(null, "class OrderService { void a() {} }\n"),
                "src/main/RefundService.java", new TextChange(null, "class RefundService { void b() {} }\n"));
        assertTrue(IndependentCodeReviewer.shouldReview(multiFile));

        ModelClient refactorModel = (messages, tools) -> new ModelResponse("",
            List.of(new ToolCall("call_1", "submit_code_review", Map.of("verdict", "REFACTOR", "issues",
                List.of("src/main/RefundService.java:1 重复实现了金额计算 -> 复用 MoneyUtils.calculate")))));
        String guidance =
            new IndependentCodeReviewer(refactorModel, workspace, () -> false).review("实现订单与退款", multiFile);
        assertNotNull(guidance);
        assertTrue(guidance.contains("MoneyUtils.calculate"), guidance);
        assertTrue(guidance.contains("重新运行对应的测试"), guidance);

        ModelClient passModel = (messages, tools) -> new ModelResponse("",
            List.of(new ToolCall("call_1", "submit_code_review", Map.of("verdict", "PASS", "issues", List.of()))));
        assertNull(new IndependentCodeReviewer(passModel, workspace, () -> false).review("实现订单与退款", multiFile));

        ModelClient brokenModel = (messages, tools) -> {
            throw new IllegalStateException("endpoint timeout");
        };
        assertNull(new IndependentCodeReviewer(brokenModel, workspace, () -> false).review("实现订单与退款", multiFile),
            "审查异常必须安全放行，不能把已通过测试的正常任务打挂");
    }

    private static void assertEqualsZero(int value) {
        assertTrue(value == 0, "expected 0 calls, got " + value);
    }
}
