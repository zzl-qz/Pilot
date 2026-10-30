package com.pilot.server.session;

import com.pilot.core.loop.ExecutionPolicy;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;
import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GoalExecutionPolicyTest {
    @TempDir Path root;
    TaskControlService tasks;
    final List<Message> history = List.of(Message.system("repo rules"), Message.user("修复重连"),
            Message.assistant("候选交付", List.of()));

    @BeforeEach void setup() {
        tasks = new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(root.resolve("data").toString()));
        tasks.start("s", "修复重连", List.of("保持接口"), List.of());
    }

    GoalExecutionPolicy policy(ModelClient model, int budget) {
        return new GoalExecutionPolicy(model, JsonMapper.builder().build(), tasks, "s", root, () -> false, budget);
    }

    GoalExecutionPolicy policy(ModelClient model, int budget, int directRequests, long directInput,
                               int investigationTools) {
        return new GoalExecutionPolicy(model, JsonMapper.builder().build(), tasks, "s", root, () -> false, budget,
                directRequests, directInput, 5_000, 60_000, investigationTools,
                2_000_000, 250_000, 1_800_000);
    }

    static ModelResponse decision(Map<String, Object> args) {
        return new ModelResponse("", List.of(new ToolCall("judge", "submit_decision", args)));
    }

    ModelResponse complete() {
        TaskControlService.Execution state = tasks.load("s").execution();
        String evidence = state.evidence().stream().filter(e -> "SUCCESS".equals(e.status()))
                .filter(e -> !"promote_goal".equals(e.tool())).findFirst().orElseThrow().id();
        return decision(Map.of("decision", "COMPLETE", "gap", "", "next_action", "",
                "expected_observation", "", "coverage", List.of(Map.of(
                "criterion", 0, "satisfied", true, "evidence", List.of(evidence)))));
    }

    static ModelResponse continueDecision(String gap) {
        return decision(Map.of("decision", "CONTINUE", "gap", gap,
                "next_action", "运行定向集成测试", "expected_observation", "集成测试成功退出",
                "coverage", List.of(Map.of("criterion", 0, "satisfied", false, "evidence", List.of()))));
    }

    void observe(GoalExecutionPolicy policy, String tool, String path, String result, String check) {
        ToolCall call = new ToolCall(UUID.randomUUID().toString(), tool,
                path.isEmpty() ? Map.of() : Map.of("path", path));
        policy.beforeTool(call);
        policy.observed(call, new ToolExecutor.Outcome(tool, result,
                Set.of("edit_file", "create_file").contains(tool) ? path : null, check));
    }

    @Test void directStartsWithoutSeparateAssessmentModelCall() {
        AtomicInteger calls = new AtomicInteger();
        GoalExecutionPolicy policy = policy((messages, tools) -> {
            calls.incrementAndGet();
            return new ModelResponse("不应调用", List.of());
        }, 20);

        policy.beforeRequest(history);
        assertEquals("DIRECT", tasks.load("s").execution().mode());
        assertEquals(0, calls.get());
        assertNull(policy.review(history, Set.of(), List.of()));
        assertEquals("VERIFIED", tasks.load("s").execution().phase());
    }

    @Test void mainAgentExplicitlyPromotesGoalWithCompletionConditions() {
        GoalExecutionPolicy policy = policy((messages, tools) -> complete(), 20);
        policy.beforeRequest(history);
        String result = policy.promotionTool().execute(Map.of("reason", "需要等待修复后的真实重连验证",
                "completion_conditions", List.of("断线后能自动重连")));

        assertTrue(result.contains("Goal 已激活"));
        TaskControlService.Execution execution = tasks.load("s").execution();
        assertEquals("GOAL", execution.mode());
        assertEquals(List.of("断线后能自动重连"), execution.criteria().stream()
                .map(TaskControlService.Criterion::outcome).toList());
        assertEquals("修复重连", tasks.load("s").originalGoal());
    }

    @Test void onlyActiveGoalUsesIndependentReviewer() {
        AtomicInteger calls = new AtomicInteger();
        GoalExecutionPolicy policy = policy((messages, tools) -> {
            calls.incrementAndGet();
            return complete();
        }, 20);
        policy.beforeRequest(history);
        assertNull(policy.review(history, Set.of(), List.of()));
        assertEquals(0, calls.get());

        policy.promotionTool().execute(Map.of("reason", "需要结果闭环",
                "completion_conditions", List.of("获得真实重连证据")));
        observe(policy, "read_file", "Reconnect.java", "实际实现包含重连状态机", null);
        assertNull(policy.review(history, Set.of(), List.of()));
        assertEquals(1, calls.get());
        assertEquals("VERIFIED", tasks.load("s").execution().phase());
    }

    @Test void directCodeChangeGetsOneValidationNudgeThenQualifiedStop() throws Exception {
        Files.writeString(root.resolve("A.java"), "v1");
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("direct must not invoke reviewer"), 20);
        policy.beforeRequest(history);
        observe(policy, "edit_file", "A.java", "updated", null);

        assertNotNull(policy.review(history, Set.of("A.java"), List.of()));
        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of("A.java"), List.of()));
        assertEquals("UNVERIFIED", stop.code());
        assertEquals("DIRECT", tasks.load("s").execution().mode(), "缺少验证不应把普通任务强制升级 Goal");
    }

    @Test void successfulCheckIsBoundToExactTaskFileVersionEvenWithLargeDirtyWorkspace() throws Exception {
        Files.writeString(root.resolve("A.java"), "x".repeat(90_000));
        Files.writeString(root.resolve("unrelated.txt"), "before");
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        observe(policy, "edit_file", "A.java", "updated", null);
        observe(policy, "run_command", "", "command completed", "mvn test → exit 0");

        assertNull(policy.review(history, Set.of("A.java"), List.of()));
        Files.writeString(root.resolve("unrelated.txt"), "after");
        assertNull(policy.review(history, Set.of("A.java"), List.of()),
                "无关文件变化不能使本任务验证失效");
        Files.writeString(root.resolve("A.java"), "changed after test");
        assertNotNull(policy.review(history, Set.of("A.java"), List.of()),
                "实际改动文件变化后必须重新验证");
    }

    @Test void correctedSubprojectChecksSupersedeEarlierWrongDirectoryFailures() throws Exception {
        Files.writeString(root.resolve("A.java"), "v1");
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        observe(policy, "edit_file", "A.java", "updated", null);
        observe(policy, "run_command", "", "ERROR: package.json not found", "npm test → exit 254");
        observe(policy, "run_command", "", "ERROR: package.json not found", "npm run check → exit 254");

        assertTrue(policy.review(history, Set.of("A.java"), List.of()).contains("工作目录"),
                "首次环境错误应给执行模型一次修正机会");
        observe(policy, "run_command", "", "command completed", "npm --prefix pilot-web test → exit 0");
        observe(policy, "run_command", "", "command completed", "npm --prefix pilot-web run check → exit 0");

        assertNull(policy.review(history, Set.of("A.java"), List.of()));
        assertEquals("VERIFIED", tasks.load("s").execution().phase());
    }

    @Test void passingStaticCheckDoesNotHideStillFailingTests() throws Exception {
        Files.writeString(root.resolve("A.java"), "v1");
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        observe(policy, "edit_file", "A.java", "updated", null);
        observe(policy, "run_command", "", "ERROR: tests failed", "npm test → exit 1");
        assertNotNull(policy.review(history, Set.of("A.java"), List.of()));
        observe(policy, "run_command", "", "command completed", "npm run check → exit 0");

        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of("A.java"), List.of()));
        assertEquals("UNVERIFIED", stop.code());
        assertTrue(stop.getMessage().contains("npm test"), stop.getMessage());
    }

    @Test void delayedBackgroundResultCannotValidateNewerSource() throws Exception {
        Files.writeString(root.resolve("A.java"), "v1");
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        observe(policy, "edit_file", "A.java", "updated", null);
        ToolCall run = new ToolCall("run", "run_command", Map.of("command", "mvn test"));
        policy.beforeTool(run);
        policy.observed(run, new ToolExecutor.Outcome("run_command", "任务ID: job1\n状态: RUNNING", null, null));
        Files.writeString(root.resolve("A.java"), "v2");
        ToolCall status = new ToolCall("status", "command_status", Map.of("taskId", "job1"));
        policy.beforeTool(status);
        policy.observed(status, new ToolExecutor.Outcome("command_status",
                "任务ID: job1\n状态: COMPLETED", null, "mvn test → exit 0"));

        assertNotNull(policy.review(history, Set.of("A.java"), List.of()));
    }

    @Test void noProgressGuardUsesRepeatedEvidenceStateNotTotalReviewCount() {
        AtomicInteger calls = new AtomicInteger();
        GoalExecutionPolicy policy = policy((messages, tools) -> {
            calls.incrementAndGet();
            return continueDecision("缺少集成证据");
        }, 20);
        policy.beforeRequest(history);
        policy.promotionTool().execute(Map.of("reason", "需要集成闭环",
                "completion_conditions", List.of("集成行为通过")));
        observe(policy, "edit_file", "A.java", "版本一", null);
        assertNotNull(policy.review(history, Set.of(), List.of()));
        observe(policy, "edit_file", "B.java", "版本二", null);
        assertNotNull(policy.review(history, Set.of(), List.of()), "真实新证据应重置无进展计数");
        assertNotNull(policy.review(history, Set.of(), List.of()));
        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of(), List.of()));
        assertEquals("NO_PROGRESS", stop.code());
        assertEquals(4, calls.get());
    }

    @Test void changingReadAndSearchQueriesDoNotResetGoalNoProgressGuard() {
        GoalExecutionPolicy policy = policy((messages, tools) -> continueDecision("缺少实现或验证"), 20);
        policy.beforeRequest(history);
        policy.promotionTool().execute(Map.of("reason", "需要闭环",
                "completion_conditions", List.of("完成真实修改并验证")));
        observe(policy, "read_file", "A.java", "读取证据一", null);
        assertNotNull(policy.review(history, Set.of(), List.of()));
        observe(policy, "search_text", "B.java", "换关键词后的读取证据二", null);
        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of(), List.of()));
        assertEquals("NO_PROGRESS", stop.code());
        assertTrue(stop.getMessage().contains("运行时停滞检查"));
    }

    @Test void configuredAccidentFuseStopsWithoutHalfBudgetBehaviorChange() {
        GoalExecutionPolicy policy = policy((messages, tools) -> new ModelResponse("继续", List.of(),
                new ModelUsage(30_000, 10, 0, true)), 120, 24, 50_000, 32);
        policy.beforeRequest(history);
        policy.budgetedClient().chat(history, List.of());
        policy.beforeRequest(history);
        assertNull(policy.requestGuidance(), "半额度不应改变任务策略");
        policy.budgetedClient().chat(history, List.of());

        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.beforeRequest(history));
        assertEquals("BUDGET_LIMITED", stop.code());
        assertTrue(stop.getMessage().contains("普通执行"));
        assertTrue(stop.getMessage().contains("累计输入 token"));
        assertTrue(stop.getMessage().contains("执行段即将结束"));
    }

    @Test void explicitPromotionUnlocksGoalBudgetWithoutAnotherClassifierCall() {
        GoalExecutionPolicy policy = policy((messages, tools) -> new ModelResponse("继续", List.of(),
                new ModelUsage(60_000, 10, 0, true)), 120, 24, 50_000, 32);
        policy.beforeRequest(history);
        policy.budgetedClient().chat(history, List.of());
        policy.promotionTool().execute(Map.of("reason", "需要跨轮等待真实结果",
                "completion_conditions", List.of("动态结果已验证")));

        assertDoesNotThrow(() -> policy.beforeRequest(history));
        assertEquals("GOAL", tasks.load("s").execution().mode());
    }

    @Test void investigationTelemetryNeverClosesLegitimateExplorationRoute() {
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("model not expected"),
                120, 24, 600_000, 8);
        policy.beforeRequest(history);
        for (int index = 0; index < 8; index++) {
            observe(policy, "read_file", "File" + index + ".java", "内容" + index, null);
        }
        ToolCall extra = new ToolCall("extra", "search_text", Map.of("path", "src", "pattern", "next"));
        assertTrue(policy.permits(extra), "遥测阈值不能成为读取/搜索的业务硬门");
    }

    @Test void exactObservationIsNotHardDeniedByPolicy() {
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("model not expected"), 20);
        policy.beforeRequest(history);
        ToolCall first = new ToolCall("first", "read_file", Map.of("path", "A.java", "start_line", 1));
        policy.beforeTool(first);
        policy.observed(first, new ToolExecutor.Outcome("read_file", "源码", null, null));
        ToolCall duplicate = new ToolCall("duplicate", "read_file", Map.of("path", "A.java", "start_line", 1));
        assertTrue(policy.permits(duplicate), "重复检测只用于 stall 证据，不应误伤模型的合法复核");
    }

    @Test void repeatedResearchFailureDisablesOnlyThatRouteAndStillAllowsDelivery() {
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        for (int index = 0; index < 3; index++) observe(policy, "web_search", "", "未找到网页结果。", null);

        ToolCall retry = new ToolCall("retry", "web_search", Map.of("query", "另一个关键词"));
        assertFalse(policy.permits(retry));
        assertTrue(policy.denialReason(retry).contains("基于已有证据交付"));
        assertNull(policy.review(history, Set.of(), List.of()), "外部检索失败不能把整个普通任务强制标成阻塞");
    }

    @Test void reviewerFailurePreservesUnverifiedResultInsteadOfLooping() {
        AtomicInteger calls = new AtomicInteger();
        GoalExecutionPolicy policy = policy((messages, tools) -> {
            calls.incrementAndGet();
            return new ModelResponse("随意文本", List.of());
        }, 20);
        policy.beforeRequest(history);
        policy.promotionTool().execute(Map.of("reason", "需要闭环",
                "completion_conditions", List.of("重连结果正确")));
        observe(policy, "read_file", "A.java", "实际实现", null);

        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of(), List.of()));
        assertEquals("VERIFICATION_FAILED", stop.code());
        assertEquals(2, calls.get());
        assertEquals("VERIFICATION_FAILED", tasks.load("s").execution().phase());
    }

    @Test void requestAndTokenBudgetSurviveResumeAndCanBeExplicitlyExtended() {
        GoalExecutionPolicy policy = policy((messages, tools) -> new ModelResponse("ok", List.of(),
                new ModelUsage(60_000, 1, 0, true)), 5, 4, 50_000, 512);
        policy.beforeRequest(history);
        policy.budgetedClient().chat(history, List.of());
        tasks.snapshot("s", "run1");

        GoalExecutionPolicy resumed = policy((messages, tools) -> fail("budget should stop first"),
                5, 4, 50_000, 512);
        assertEquals("BUDGET_LIMITED", assertThrows(ExecutionPolicy.Stop.class,
                () -> resumed.beforeRequest(history)).code());
        tasks.status("s", "BUDGET_LIMITED");
        tasks.extendBudget("s");
        tasks.status("s", "ACTIVE");
        resumed.beforeRequest(history);
        assertEquals(60_000, tasks.load("s").execution().usage().inputTokens());
        assertEquals(1, tasks.load("s").execution().budgetSegments());
    }

    @Test void arbitrarySuccessfulCommandCannotValidateCodeChange() {
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        observe(policy, "edit_file", "A.java", "updated", null);
        observe(policy, "run_command", "", "working tree clean", "git status → exit 0");

        assertNotNull(policy.review(history, Set.of("A.java"), List.of()));
        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of("A.java"), List.of()));
        assertEquals("UNVERIFIED", stop.code());
        assertTrue(policy.priorChecks().isEmpty(), "任意 exit 0 不得污染历史验证事实");
    }

    @Test void directResumeRetainsHistoricalChangedPathsAndStillRequiresVerification() {
        GoalExecutionPolicy first = policy((messages, tools) -> fail("reviewer not expected"), 20);
        first.beforeRequest(history);
        observe(first, "edit_file", "A.java", "updated", null);

        GoalExecutionPolicy resumed = policy((messages, tools) -> fail("reviewer not expected"), 20);
        resumed.beforeRequest(history);
        assertEquals(Set.of("A.java"), resumed.priorChangedPaths());
        assertNotNull(resumed.review(history, Set.of(), List.of()));
        assertEquals("UNVERIFIED", assertThrows(ExecutionPolicy.Stop.class,
                () -> resumed.review(history, Set.of(), List.of())).code());
    }

    @Test void structuredSubagentOutcomePersistsAllPathsAndChecks() {
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        ToolCall call = new ToolCall("spawn", "spawn_agent", Map.of("task", "implement child change"));
        policy.beforeTool(call);

        policy.observed(call, new ToolExecutor.Outcome("spawn_agent", "child completed",
                List.of("src/A.java", "src/B.java"),
                List.of("mvn test → exit 0", "npm test → exit 0"),
                ToolExecutor.OutcomeStatus.SUCCESS));

        TaskControlService.Execution state = tasks.load("s").execution();
        assertEquals(Set.of("src/A.java", "src/B.java"), Set.copyOf(state.changedPaths()));
        assertEquals(List.of("mvn test → exit 0", "npm test → exit 0"), state.evidence().stream()
                .filter(e -> "spawn_agent".equals(e.tool()) && e.check() != null)
                .map(TaskControlService.Evidence::check).toList());
    }

    @Test void structuredChecksUseTheirOwnStatusInsteadOfOuterToolStatus() {
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);

        ToolCall successfulChild = new ToolCall("spawn-ok", "spawn_agent", Map.of("task", "child"));
        policy.beforeTool(successfulChild);
        policy.observed(successfulChild, new ToolExecutor.Outcome("spawn_agent", "child summary",
                List.of("src/A.java"), List.of("mvn test → exit 1", "npm test → exit 0"),
                ToolExecutor.OutcomeStatus.SUCCESS));

        ToolCall failedChild = new ToolCall("spawn-failed", "spawn_agent", Map.of("task", "child failed late"));
        policy.beforeTool(failedChild);
        policy.observed(failedChild, new ToolExecutor.Outcome("spawn_agent", "ERROR: child finalization failed",
                List.of("src/B.java"), List.of("mvn test → exit 0"),
                ToolExecutor.OutcomeStatus.PERMANENT_FAILURE));

        List<TaskControlService.Evidence> spawn = tasks.load("s").execution().evidence().stream()
                .filter(e -> "spawn_agent".equals(e.tool())).toList();
        assertEquals(List.of("PERMANENT_FAILURE", "SUCCESS", "SUCCESS"), spawn.stream()
                .filter(e -> e.check() != null).map(TaskControlService.Evidence::status).toList());
        assertEquals(List.of("SUCCESS", "PERMANENT_FAILURE"), spawn.stream()
                .filter(e -> e.check() == null).map(TaskControlService.Evidence::status).toList(),
                "外层工具结果必须与逐条 check 分开记账");
    }

    @Test void reviewerCannotUseEvidenceFromAnOlderSourceVersion() throws Exception {
        Files.writeString(root.resolve("A.java"), "v1");
        java.util.concurrent.atomic.AtomicReference<String> staleEvidence = new java.util.concurrent.atomic.AtomicReference<>();
        GoalExecutionPolicy policy = policy((messages, tools) -> decision(Map.of(
                "decision", "COMPLETE", "gap", "", "next_action", "", "expected_observation", "",
                "coverage", List.of(Map.of("criterion", 0, "satisfied", true,
                        "evidence", List.of(staleEvidence.get()))))), 20);
        policy.beforeRequest(history);
        policy.promotionTool().execute(Map.of("reason", "需要当前版本闭环",
                "completion_conditions", List.of("当前实现满足重连要求")));
        observe(policy, "edit_file", "A.java", "implemented v1", null);
        staleEvidence.set(tasks.load("s").execution().evidence().stream()
                .filter(e -> "edit_file".equals(e.tool())).findFirst().orElseThrow().id());

        Files.writeString(root.resolve("A.java"), "v2 after old behavior evidence");
        observe(policy, "run_command", "", "compiled", "mvn compile → exit 0");

        String continuation = policy.review(history, Set.of("A.java"), List.of());
        assertNotNull(continuation, "旧版本功能证据 + 当前版本任意构建不能通过 Goal");
        assertEquals("EXECUTING", tasks.load("s").execution().phase());
        assertTrue(tasks.load("s").execution().gap().contains("可追溯"));
    }

    @Test void pendingBackgroundLaunchVersionSurvivesEvidenceTrimming() throws Exception {
        Files.writeString(root.resolve("A.java"), "v1");
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("reviewer not expected"), 20);
        policy.beforeRequest(history);
        observe(policy, "edit_file", "A.java", "updated", null);
        ToolCall launch = new ToolCall("run", "run_command", Map.of("command", "mvn test"));
        policy.beforeTool(launch);
        policy.observed(launch, new ToolExecutor.Outcome("run_command",
                "任务ID: old-job\n状态: RUNNING", List.of(), List.of(),
                ToolExecutor.OutcomeStatus.RUNNING));
        String launchVersion = tasks.load("s").execution().evidence().stream()
                .filter(e -> "run_command".equals(e.tool()) && e.result().contains("old-job"))
                .findFirst().orElseThrow().startVersion();

        for (int index = 0; index < 170; index++) {
            observe(policy, "read_file", "File" + index + ".java", "observation " + index, null);
        }
        assertTrue(tasks.load("s").execution().evidence().stream()
                .anyMatch(e -> "run_command".equals(e.tool()) && e.result().contains("old-job")),
                "仍在运行的 task 启动版本不能被普通观察挤出账本");

        Files.writeString(root.resolve("A.java"), "v2");
        ToolCall status = new ToolCall("status", "command_status", Map.of("taskId", "old-job"));
        policy.beforeTool(status);
        policy.observed(status, new ToolExecutor.Outcome("command_status",
                "任务ID: old-job\n状态: COMPLETED", null, "mvn test → exit 0"));

        TaskControlService.Evidence terminalCheck = tasks.load("s").execution().evidence().stream()
                .filter(e -> "command_status".equals(e.tool()) && "mvn test → exit 0".equals(e.check()))
                .findFirst().orElseThrow();
        assertEquals(launchVersion, terminalCheck.endVersion(),
                "后台测试必须绑定启动时源码，不能偷换成查询终态时的新版本");
        assertNotNull(policy.review(history, Set.of("A.java"), List.of()));
    }

    @Test void reviewerRetriesOneTransientFailureButNotPermanentFailure() {
        AtomicInteger transientCalls = new AtomicInteger();
        GoalExecutionPolicy transientPolicy = policy((messages, tools) -> {
            if (transientCalls.getAndIncrement() == 0) {
                throw new com.pilot.core.model.ModelClientException("HTTP 503: reviewer unavailable");
            }
            return complete();
        }, 20);
        transientPolicy.beforeRequest(history);
        transientPolicy.promotionTool().execute(Map.of("reason", "需要闭环",
                "completion_conditions", List.of("重连结果正确")));
        observe(transientPolicy, "read_file", "A.java", "actual implementation", null);

        assertNull(transientPolicy.review(history, Set.of(), List.of()));
        assertEquals(2, transientCalls.get());

        tasks.start("permanent", "修复重连", List.of(), List.of());
        AtomicInteger permanentCalls = new AtomicInteger();
        GoalExecutionPolicy permanent = new GoalExecutionPolicy((messages, tools) -> {
            permanentCalls.incrementAndGet();
            throw new com.pilot.core.model.ModelClientException("HTTP 400: invalid request");
        }, JsonMapper.builder().build(), tasks, "permanent", root, () -> false, 20);
        permanent.beforeRequest(history);
        permanent.promotionTool().execute(Map.of("reason", "需要闭环",
                "completion_conditions", List.of("重连结果正确")));
        ToolCall read = new ToolCall("read", "read_file", Map.of("path", "A.java"));
        permanent.beforeTool(read);
        permanent.observed(read, new ToolExecutor.Outcome("read_file", "actual implementation", null, null));

        assertEquals("VERIFICATION_FAILED", assertThrows(ExecutionPolicy.Stop.class,
                () -> permanent.review(history, Set.of(), List.of())).code());
        assertEquals(1, permanentCalls.get());
    }

    @Test void reviewerHasTwoReservedRequestsAfterMainBudgetIsConsumed() {
        AtomicInteger calls = new AtomicInteger();
        GoalExecutionPolicy policy = policy((messages, tools) -> {
            calls.incrementAndGet();
            return new ModelResponse("not a structured decision", List.of());
        }, 4);
        policy.beforeRequest(history);
        policy.promotionTool().execute(Map.of("reason", "需要闭环",
                "completion_conditions", List.of("获得真实结果")));
        for (int index = 0; index < 4; index++) {
            policy.beforeRequest(history);
            policy.budgetedClient().chat(history, List.of());
        }

        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of(), List.of()));
        assertEquals("VERIFICATION_FAILED", stop.code());
        assertEquals(6, calls.get(), "主执行满额后 reviewer 仍可用预留的两次请求");
    }

    @Test void subagentUsageCountsTowardParentRequestAndTokenFuse() {
        GoalExecutionPolicy policy = policy((messages, tools) -> fail("raw model not expected"),
                120, 24, 50_000, 512);
        policy.beforeRequest(history);
        SpawnAgentTool.UsageSink usage = policy.subagentUsageSink();

        usage.beforeRequest();
        usage.afterRequest(37, new ModelUsage(60_000, 12, 3, true));

        TaskControlService.Execution state = tasks.load("s").execution();
        assertEquals(1, state.requests());
        assertEquals(37, state.activeMillis());
        assertEquals(60_000, state.usage().inputTokens());
        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.beforeRequest(history));
        assertEquals("BUDGET_LIMITED", stop.code());
        assertTrue(stop.getMessage().contains("累计输入 token"));
    }

    @Test void subagentWorkCannotConsumeReviewerReserve() {
        AtomicInteger reviewerCalls = new AtomicInteger();
        GoalExecutionPolicy policy = policy((messages, tools) -> {
            reviewerCalls.incrementAndGet();
            return new ModelResponse("invalid reviewer output", List.of());
        }, 4);
        policy.beforeRequest(history);
        policy.promotionTool().execute(Map.of("reason", "需要闭环",
                "completion_conditions", List.of("获得真实结果")));
        SpawnAgentTool.UsageSink usage = policy.subagentUsageSink();
        for (int index = 0; index < 4; index++) {
            usage.beforeRequest();
            usage.afterRequest(1, ModelUsage.unknown());
        }

        ExecutionPolicy.Stop stop = assertThrows(ExecutionPolicy.Stop.class,
                () -> policy.review(history, Set.of(), List.of()));

        assertEquals("VERIFICATION_FAILED", stop.code());
        assertEquals(2, reviewerCalls.get());
        assertEquals(6, tasks.load("s").execution().requests(),
                "四次子工作请求后仍只能使用两次 reviewer 预留");
    }

    @Test void goalContinuationUsesPersistedSingleActionContract() {
        GoalExecutionPolicy policy = policy((messages, tools) -> continueDecision("缺少集成证据"), 20);
        policy.beforeRequest(history);
        policy.promotionTool().execute(Map.of("reason", "需要闭环",
                "completion_conditions", List.of("集成行为通过")));
        observe(policy, "read_file", "A.java", "已发现重连路径", null);

        String guidance = policy.review(history, Set.of(), List.of());
        assertTrue(guidance.contains("Goal 续轮运行时合同"));
        assertTrue(guidance.contains("\"next_action\":\"运行定向集成测试\""));
        TaskControlService.Execution saved = tasks.load("s").execution();
        assertEquals("运行定向集成测试", saved.nextAction());
        assertEquals("集成测试成功退出", saved.expectedObservation());

        GoalExecutionPolicy resumed = policy((messages, tools) -> fail("model not expected"), 20);
        resumed.beforeRequest(history);
        assertTrue(resumed.requestGuidance().contains("\"gap\":\"缺少集成证据\""));
    }

    @Test void legacyAssessmentMigratesWithoutCallingClassifier() {
        TaskControlService.TaskContract task = tasks.load("s");
        TaskControlService.Execution old = new TaskControlService.Execution("PROBE", "旧预判", List.of(), List.of(),
                7, 100, List.of(), "EXPLORING", "", 0, "");
        assertTrue(tasks.execution("s", task, old));
        AtomicInteger calls = new AtomicInteger();
        GoalExecutionPolicy policy = policy((messages, tools) -> {
            calls.incrementAndGet();
            return new ModelResponse("unused", List.of());
        }, 20);
        policy.beforeRequest(history);
        assertEquals("DIRECT", tasks.load("s").execution().mode());
        assertEquals(0, calls.get());
        assertEquals(7, tasks.load("s").execution().requests());
    }
}
