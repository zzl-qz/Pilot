package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pilot.core.lint.LintGate;
import com.pilot.core.lint.LintRunner;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.quality.IndependentCodeReviewer;
import com.pilot.core.quality.TextChange;

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
    void explicitReadOnlyDeliveryDoesNotRequestRepositoryChanges() {
        for (String goal : List.of("实现与验证已完成，本轮只读收尾，请阅读报告并总结", "总结已经实现的代码，不要修改任何仓库文件",
            "Summarize the implemented changes. Do not edit any files.")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, goal);
            assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of()), goal);
        }
    }

    @Test
    void readOnlyHandoffDoesNotResumeOldImplementationPlanOrWaiveVerification() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "本轮只读收尾，不要修改任何仓库文件");
        assertNull(gate.evaluate(Set.of(), List.of(), List.of(), List.of("实现登录接口")));

        CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of(), List.of(), List.of());
        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("未验证"));
    }

    @Test
    void implementingReadOnlyFeaturesAndProtectingSpecificFilesStillRequiresAction() {
        for (String goal : List.of("实现只读接口，不要修改测试文件", "修复读取问题，不要修改 package.json", "修复只读收尾误触发写代码的 bug",
            "Implement a read-only API. Do not edit the tests.")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, goal);
            CompletionGate.Nudge nudge = gate.evaluate(Set.of(), List.of(), List.of(), List.of());
            assertNotNull(nudge, goal);
            assertTrue(nudge.reason().contains("动手"), goal);
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
        CompletionGate.Nudge nudge =
            gate.evaluate(changed, List.of("node --test | tail -12 → exit 0"), List.of(), List.of());

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
            "node --test → exit 0", "python3 -m pytest -q → exit 0", "cargo check → exit 0", "go vet ./... → exit 0")) {
            CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

            CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/A.java"), List.of(check), List.of(), List.of());

            assertNotNull(nudge, check);
            assertTrue(nudge.reason().contains("自审"), check + ": " + nudge.reason());
        }
    }

    @Test
    void formatOnlyCheckDoesNotVerifyCodeChanges() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        CompletionGate.Nudge nudge =
            gate.evaluate(Set.of("src/A.java"), List.of("git diff --check → exit 0"), List.of(), List.of());

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("未验证"), nudge.reason());
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
    void passingCheckWithoutALaterEditDoesNotAskToRerun() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修一个 bug");

        CompletionGate.Nudge nudge =
            gate.evaluate(Set.of("src/A.java"), List.of("mvn test → exit 0"), List.of(), List.of());

        assertNotNull(nudge);
        assertFalse(nudge.reason().contains("未验证"), nudge.reason());
        assertTrue(nudge.reason().contains("自审"), nudge.reason());
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
    void lintFindingsAreRecordedAndDoNotBlockByDefault() throws Exception {
        Path root = Files.createTempDirectory("lint-gate");
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"node lint.js\"}}");
        LintRunner runner = (workspace, command, timeout) -> new LintRunner.LintResult(1,
            "src/main/Order.java:2 error 魔法值 86400\n", false, true);
        LintGate lint = new LintGate(true, runner);
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修复订单状态", lint);
        Map<String, TextChange> changes = Map.of("src/main/Order.java",
            new TextChange("class Order {}\n", "class Order {\n    int timeout = 86400;\n}\n"));

        CompletionGate.Nudge nudge =
            gate.evaluate(Set.of("src/main/Order.java"), List.of(), List.of(), List.of(), false, changes, root, null);

        assertNotNull(nudge);
        assertTrue(!nudge.halt());
        assertTrue(nudge.reason().contains("未验证"), nudge.reason());
        assertTrue(lint.releaseNote().contains("86400"), lint.releaseNote());
    }

    @Test
    void lintFindingsStopTheTaskWhenPolicySaysStop() throws Exception {
        Path root = Files.createTempDirectory("lint-gate-stop");
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"node lint.js\"}}");
        LintRunner runner = (workspace, command, timeout) -> new LintRunner.LintResult(1,
            "src/main/Order.java:2 error 魔法值 86400\n", false, true);
        CompletionGate gate =
            new CompletionGate(PROMPTS, true, "修复订单状态", new LintGate(true, runner, LintGate.FailurePolicy.STOP));

        CompletionGate.Nudge nudge =
            gate.evaluate(Set.of("src/main/Order.java"), List.of(), List.of(), List.of(), false, Map.of(), root, null);

        assertNotNull(nudge);
        assertTrue(nudge.halt(), nudge.reason());
        assertTrue(nudge.prompt().contains("86400"), nudge.prompt());
    }

    @Test
    void lintSwitchOffDoesNotBlockOnTheSameFinding() {
        LintRunner runner = (workspace, command, timeout) -> {
            throw new AssertionError("开关关闭时不应执行 lint");
        };
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修复订单状态", new LintGate(false, runner));

        CompletionGate.Nudge nudge = gate.evaluate(Set.of("src/main/Order.java"), List.of(), List.of(), List.of(),
            false, Map.of(), Path.of("."), null);

        assertNotNull(nudge);
        assertTrue(nudge.reason().contains("未验证"), nudge.reason());
    }

    @Test
    void refactoringAfterPassingTestRequiresFreshVerificationSoBehaviorCannotBreakSilently() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "实现订单与退款服务");
        Set<String> changed = Set.of("src/main/OrderService.java", "src/main/RefundService.java");
        Map<String, TextChange> cleanChanges =
            Map.of("src/main/OrderService.java", new TextChange(null, "class OrderService {}\n"),
                "src/main/RefundService.java", new TextChange(null, "class RefundService {}\n"));

        // 第一步：写代码并跑通测试
        gate.recordOutcome(changed, List.of());
        gate.recordOutcome(List.of(), List.of("mvnw test → exit 0"));

        // 第二步：收尾时独立代码审查发现重复逻辑，要求精简重构
        IndependentCodeReviewer reviewer =
            new IndependentCodeReviewer(
                (messages,
                    tools) -> new ModelResponse("",
                        List.of(new ToolCall("r1", "submit_code_review",
                            Map.of("verdict", "REFACTOR", "issues",
                                List.of("OrderService 与 RefundService 重复计算逻辑 -> 抽离公共方法"))))),
                null, PROMPTS, () -> false);

        CompletionGate.Nudge reviewNudge = gate.evaluate(changed, List.of("mvnw test → exit 0"), List.of(), List.of(),
            false, cleanChanges, null, reviewer);
        assertNotNull(reviewNudge);
        assertTrue(reviewNudge.reason().contains("独立代码精简审查"), reviewNudge.reason());

        // 第三步：模型按建议修改了代码，但还没重跑测试就想直接交差 -> 必须拦住重跑验证！
        gate.recordOutcome(List.of("src/main/OrderService.java"), List.of());
        CompletionGate.Nudge reverifyNudge = gate.evaluate(changed, List.of("mvnw test → exit 0"), List.of(), List.of(),
            false, cleanChanges, null, reviewer);
        assertNotNull(reverifyNudge);
        assertTrue(reverifyNudge.reason().contains("尚未重新验证"), reverifyNudge.reason());

        // 第四步：重跑测试通过后干净收尾，不再死循环
        gate.recordOutcome(List.of(), List.of("mvnw test → exit 0"));
        assertNull(gate.evaluate(changed, List.of("mvnw test → exit 0"), List.of(), List.of(), false, cleanChanges,
            null, reviewer));
    }

    @Test
    void passingIndependentCodeReviewWithPassingTestsAndCompletePlanSkipsDuplicateSelfReview() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "实现订单与退款服务");
        Set<String> changed = Set.of("src/main/OrderService.java", "src/main/RefundService.java");
        Map<String, TextChange> cleanChanges =
            Map.of("src/main/OrderService.java", new TextChange(null, "class OrderService {}\n"),
                "src/main/RefundService.java", new TextChange(null, "class RefundService {}\n"));
        gate.recordOutcome(changed, List.of());
        gate.recordOutcome(List.of(), List.of("mvnw test → exit 0"));

        IndependentCodeReviewer passingReviewer = new IndependentCodeReviewer(
            (messages, tools) -> new ModelResponse("",
                List.of(new ToolCall("r1", "submit_code_review", Map.of("verdict", "PASS", "issues", List.of())))),
            null, PROMPTS, () -> false);

        assertNull(gate.evaluate(changed, List.of("mvnw test → exit 0"),
            List.of(new PlanItem("完成服务", PlanItem.COMPLETED)), List.of(), false, cleanChanges, null, passingReviewer));
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

    @Test
    void boundaryHardeningGoalRequiresBoundaryTestsAndReverificationBoundedToOnce() {
        CompletionGate gate = new CompletionGate(PROMPTS, true, "完善字符串解析器的边界情况与鲁棒性");
        Map<String, TextChange> cleanChanges = Map.of("src/main/Parser.java",
            new TextChange("class Parser {}\n", "class Parser { int parse() { return 0; } }\n"));

        gate.recordOutcome(List.of("src/main/Parser.java"), List.of());
        gate.recordOutcome(List.of(), List.of("mvnw test → exit 0"));

        // 只改了生产代码没补单测 -> 证据门拦截一次
        CompletionGate.Nudge evidenceNudge = gate.evaluate(Set.of("src/main/Parser.java"),
            List.of("mvnw test → exit 0"), List.of(), List.of(), true, cleanChanges, null, null, "边界情况已全部处理完毕！");
        assertNotNull(evidenceNudge);
        assertTrue(evidenceNudge.reason().contains("硬证据不足"), evidenceNudge.reason());
        assertTrue(evidenceNudge.prompt().contains("边界单元测试"), evidenceNudge.prompt());

        // 模型补了单测文件但漏跑测试 -> dirty check 拦住要求跑通新单测
        Set<String> withTest = Set.of("src/main/Parser.java", "src/test/ParserTest.java");
        gate.recordOutcome(List.of("src/test/ParserTest.java"), List.of());
        CompletionGate.Nudge reverify = gate.evaluate(withTest, List.of("mvnw test → exit 0"), List.of(), List.of(),
            true, cleanChanges, null, null, "已补齐边界单测。");
        assertNotNull(reverify);
        assertTrue(reverify.reason().contains("尚未重新验证"), reverify.reason());

        // 跑通测试后放行，绝不无限循环
        gate.recordOutcome(List.of(), List.of("mvnw test → exit 0"));
        assertNull(gate.evaluate(withTest, List.of("mvnw test → exit 0"), List.of(), List.of(), true, cleanChanges,
            null, null, "已补齐边界单测并验证通过。"));
    }

    @Test
    void retryAsksForAStyleFixOnlyAfterTestsPassAndThenReleases() throws Exception {
        Path root = Files.createTempDirectory("lint-retry");
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"node lint.js\"}}");
        LintRunner runner = (workspace, command, timeout) -> new LintRunner.LintResult(1,
            "src/main/Order.java:2 error 魔法值 86400\n", false, true);
        LintGate lint = new LintGate(true, runner, LintGate.FailurePolicy.RETRY);
        CompletionGate gate = new CompletionGate(PROMPTS, true, "修复订单状态", lint);
        gate.recordOutcome(List.of(), List.of("mvnw test → exit 0"));
        Set<String> changed = Set.of("src/main/Order.java");
        List<String> checks = List.of("mvnw test → exit 0");

        CompletionGate.Nudge first = gate.evaluate(changed, checks, List.of(), List.of(), false, Map.of(), root, null);
        assertNotNull(first);
        assertTrue(!first.halt());
        assertTrue(first.prompt().contains("86400"), first.prompt());

        assertNotNull(gate.evaluate(changed, checks, List.of(), List.of(), false, Map.of(), root, null));
        CompletionGate.Nudge third = gate.evaluate(changed, checks, List.of(), List.of(), false, Map.of(), root, null);
        if (third != null) {
            assertTrue(!third.halt(), third.reason());
        }
        assertTrue(lint.releaseNote().contains("到上限"), lint.releaseNote());
    }

    @Test
    void retryDoesNotReleaseWhenTheCodeWasNeverVerified() throws Exception {
        Path root = Files.createTempDirectory("lint-retry-unverified");
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"node lint.js\"}}");
        LintRunner runner = (workspace, command, timeout) -> new LintRunner.LintResult(1,
            "src/main/Order.java:2 error 魔法值 86400\n", false, true);
        CompletionGate gate =
            new CompletionGate(PROMPTS, true, "修复订单状态", new LintGate(true, runner, LintGate.FailurePolicy.RETRY));

        Set<String> changed = Set.of("src/main/Order.java");
        CompletionGate.Nudge first =
            gate.evaluate(changed, List.of(), List.of(), List.of(), false, Map.of(), root, null);
        assertNotNull(first);
        assertTrue(!first.halt());
        assertTrue(first.reason().contains("未验证"), first.reason());

        gate.evaluate(changed, List.of(), List.of(), List.of(), false, Map.of(), root, null);
        CompletionGate.Nudge blocked =
            gate.evaluate(changed, List.of(), List.of(), List.of(), false, Map.of(), root, null);
        assertNotNull(blocked);
        assertTrue(blocked.halt(), blocked.reason());
        assertTrue(blocked.reason().contains("不能放行"), blocked.reason());
    }

    @Test
    void retryStillHaltsWhenTheLintRulesAreEdited() throws Exception {
        Path root = Files.createTempDirectory("lint-retry-tamper");
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"node lint.js\"}}");
        Files.createDirectories(root.resolve(".pilot"));
        Files.writeString(root.resolve(".pilot/settings.json"), "{\"quality\":{\"command\":\"exit 0\"}}");
        LintRunner runner = (workspace, command, timeout) -> new LintRunner.LintResult(0, "", false, true);
        CompletionGate gate =
            new CompletionGate(PROMPTS, true, "修复订单状态", new LintGate(true, runner, LintGate.FailurePolicy.RETRY));

        CompletionGate.Nudge nudge = gate.evaluate(Set.of(".pilot/settings.json"), List.of("mvnw test → exit 0"),
            List.of(), List.of(), false, Map.of(), root, null);
        assertNotNull(nudge);
        assertTrue(nudge.halt(), nudge.reason());
        assertTrue(nudge.prompt().contains("被改过"), nudge.prompt());
    }
}
