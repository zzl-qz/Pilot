package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.session.InMemoryConversationRepository;
import com.pilot.core.session.InMemoryPlanRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.observability.RunMetrics;
import com.pilot.server.store.InMemorySettingsStore;
import com.pilot.server.store.QueuedMessageRepository;
import com.pilot.server.workspace.RunSnapshotService;
import com.pilot.server.workspace.WorkspaceToolFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.MockMakers.SUBCLASS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class AgentRunServiceTest {
    @TempDir
    Path workspace;
    @TempDir
    Path dataRoot;

    @Test
    void aggregatesChildUsageWithoutReplacingMainRunSnapshot() {
        AgentRunService.RunUsageAccumulator usage = new AgentRunService.RunUsageAccumulator();

        RunUsageUpdated main = usage.main(new RunUsageUpdated(2, 1_000, 100,
                700, 8_000, true));
        RunUsageUpdated child = usage.child(new ModelUsage(300, 30, 0, true));
        RunUsageUpdated nextMain = usage.main(new RunUsageUpdated(3, 1_500, 150,
                900, 8_000, true));
        RunUsageUpdated unknownChild = usage.child(ModelUsage.unknown());

        assertEquals(2, main.modelRequests());
        assertEquals(3, child.modelRequests());
        assertEquals(1_300, child.inputTokens());
        assertEquals(4, nextMain.modelRequests(), "新的主快照必须继续叠加已有子请求，而不是倒退");
        assertEquals(1_800, nextMain.inputTokens());
        assertEquals(900, nextMain.contextTokens(), "上下文水位始终来自最近主请求");
        assertEquals(5, unknownChild.modelRequests());
        assertFalse(unknownChild.exact(), "任一来源缺少精确 usage 时聚合结果必须标记为估算");
    }

    @Test
    void waitsForTaskCompletionThenStartsEditedMessagesInOrder() throws Exception {
        CountDownLatch enteredFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch enteredSecond = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<List<Message>>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            if (tools.isEmpty() && messages.getLast().content().startsWith("[系统收尾要求]")) {
                return new ModelResponse("本轮达到步数边界，进度已保存。", List.of());
            }
            return switch (calls.getAndIncrement()) {
                case 0 -> {
                    enteredFirst.countDown();
                    await(releaseFirst);
                    yield readFile();
                }
                case 1 -> {
                    enteredSecond.countDown();
                    await(releaseSecond);
                    yield done();
                }
                default -> done();
            };
        };
        Files.writeString(workspace.resolve("a.txt"), "original task evidence");
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            await(enteredFirst);
            assertTrue(fixture.service.followup(fixture.sessionId, "下一条"));
            assertTrue(fixture.service.followup(fixture.sessionId, "再下一条"));
            assertTrue(fixture.service.followup(fixture.sessionId, "删除这条"));
            releaseFirst.countDown();
            await(enteredSecond);

            // 已完成一次工具调用，但原任务仍在采样；排队消息不能进入这次模型请求。
            assertEquals(List.of("下一条", "再下一条", "删除这条"), fixture.queue());
            assertEquals(List.of("原任务"), userInputs(requests.get(1)));
            assertTrue(fixture.service.editQueued(fixture.sessionId, 0, "修改后的下一条"));
            assertTrue(fixture.service.removeQueued(fixture.sessionId, 2));
            releaseSecond.countDown();
            fixture.awaitFinished("COMPLETED", "COMPLETED", "COMPLETED");

            assertEquals(List.of(), fixture.queue());
            assertEquals(List.of("原任务", "修改后的下一条"), userInputs(requests.get(2)));
            assertEquals(List.of("原任务", "修改后的下一条", "再下一条"), userInputs(requests.get(3)));
            List<EventEnvelope> events = fixture.sessions.events(fixture.sessionId);
            assertFalse(events.stream().anyMatch(event -> event.type().equals("run.steered")));
            EventEnvelope firstCompleted = events.stream().filter(event -> event.payload() instanceof RunCompleted)
                    .findFirst().orElseThrow();
            EventEnvelope nextStarted = events.stream().filter(event -> event.payload() instanceof RunStarted started
                    && started.input().equals("修改后的下一条")).findFirst().orElseThrow();
            assertTrue(firstCompleted.sequence() < nextStarted.sequence());
            assertNotEquals(firstCompleted.runId(), nextStarted.runId());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @Test
    void queuedImagesSurviveEditsAndReachTheNextRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<List<Message>>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
            }
            return done();
        };
        Attachment image = new Attachment("shot.png", "image/png", "/stable/attachments/shot.png");
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.followup(fixture.sessionId, "看这张图", List.of(image), List.of(2)));
            assertTrue(fixture.service.followup(fixture.sessionId, "纯文字"));
            assertEquals(List.of("看这张图", "纯文字"), fixture.queue());
            List<RunStarter.QueuedMessage> queued = fixture.service.queuedMessages(fixture.sessionId);
            assertEquals(List.of(image), queued.get(0).attachments());
            assertEquals(List.of(), queued.get(1).attachments());

            // 往前排时文字和图片一起走，不能出现文字换了位置图片还留在原地
            assertTrue(fixture.service.moveQueuedUp(fixture.sessionId, 1));
            queued = fixture.service.queuedMessages(fixture.sessionId);
            assertEquals(List.of("纯文字", "看这张图"), fixture.queue());
            assertEquals(List.of(), queued.get(0).attachments());
            assertEquals(List.of(image), queued.get(1).attachments());
            assertFalse(fixture.service.moveQueuedUp(fixture.sessionId, 0));
            assertTrue(fixture.service.moveQueuedUp(fixture.sessionId, 1));
            assertEquals(List.of("看这张图", "纯文字"), fixture.queue());

            // 拖拽排序：一次挪到目标位置，图片跟着文字走；越界或原地不动都拒绝
            assertTrue(fixture.service.followup(fixture.sessionId, "第三句"));
            assertTrue(fixture.service.moveQueued(fixture.sessionId, 0, 2));
            assertEquals(List.of("纯文字", "第三句", "看这张图"), fixture.queue());
            assertEquals(List.of(image), fixture.service.queuedMessages(fixture.sessionId).get(2).attachments());
            assertTrue(fixture.service.moveQueued(fixture.sessionId, 2, 0));
            assertEquals(List.of("看这张图", "纯文字", "第三句"), fixture.queue());
            assertFalse(fixture.service.moveQueued(fixture.sessionId, 1, 1));
            assertFalse(fixture.service.moveQueued(fixture.sessionId, 0, 3));
            assertFalse(fixture.service.moveQueued(fixture.sessionId, -1, 0));
            assertTrue(fixture.service.removeQueued(fixture.sessionId, 2));
            assertEquals(List.of("看这张图", "纯文字"), fixture.queue());

            assertTrue(fixture.service.editQueued(fixture.sessionId, 0, "改成这样看图"));
            queued = fixture.service.queuedMessages(fixture.sessionId);
            assertEquals("改成这样看图", queued.get(0).content());
            assertEquals(List.of(image), queued.get(0).attachments(), "只改文字，图片要跟着留下");
            assertEquals(List.of(2), queued.get(0).imagePositions());

            release.countDown();
            fixture.awaitFinished("COMPLETED", "COMPLETED", "COMPLETED");

            Message delivered = requests.get(1).stream()
                    .filter(message -> "user".equals(message.role()) && "改成这样看图".equals(message.content()))
                    .findFirst().orElseThrow();
            assertEquals(List.of(image), delivered.attachments());
            assertEquals(List.of(2), delivered.imagePositions());
            RunStarted started = fixture.sessions.events(fixture.sessionId).stream()
                    .map(event -> event.payload())
                    .filter(payload -> payload instanceof RunStarted run && "改成这样看图".equals(run.input()))
                    .map(payload -> (RunStarted) payload)
                    .findFirst().orElseThrow();
            assertEquals(List.of(image), started.attachments());
            assertEquals(List.of(), fixture.queue());
        } finally {
            release.countDown();
        }
    }

    @Test
    void countsToolAndCompletionEventsOncePerRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> calls.getAndIncrement() == 0 ? readFile() : done();
        Files.writeString(workspace.resolve("a.txt"), "evidence");

        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            RunMetrics.Snapshot metrics = fixture.metrics.snapshot();
            assertEquals(1L, metrics.runs());
            for (String event : List.of("event.tool.started", "event.tool.completed", "event.run.completed")) {
                assertEquals(1L, metrics.counters().getOrDefault(event, 0L).longValue(), event);
            }
            assertEquals(2L, metrics.counters().getOrDefault("event.assistant.delta", 0L).longValue());
        }
    }

    @Test
    void countsFailedRunEvents() throws Exception {
        ModelClient model = (messages, tools) -> {
            throw new IllegalArgumentException("invalid model request");
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            fixture.awaitFinished("FAILED");

            RunMetrics.Snapshot metrics = fixture.metrics.snapshot();
            assertEquals(1L, metrics.runs());
            assertEquals(1L, metrics.counters().getOrDefault("event.run.failed", 0L).longValue());
            assertEquals(1L, metrics.counters().getOrDefault("run.FAILED", 0L).longValue());
        }
    }

    @Test
    void keepsQueueWhenModelFails() throws Exception {
        assertKeepsQueue(8, List.of(), () -> { throw new IllegalArgumentException("invalid model request"); }, "FAILED");
    }

    @Test
    void injectsRepositoryBriefOncePerSession() throws Exception {
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<List<Message>>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            return done();
        };
        Files.writeString(workspace.resolve("a.txt"), "evidence");
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            // 第二次 run：历史非空，不应重复注入
            String secondRun = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.service.start(fixture.sessionId, secondRun, "第二个任务");
            fixture.awaitFinished("COMPLETED");

            List<Message> first = requests.get(0);
            assertEquals(1, snapshotCount(first), "会话首条请求应恰好注入一次仓库快照");
            int snapshotAt = -1;
            int inputAt = -1;
            for (int i = 0; i < first.size(); i++) {
                Message message = first.get(i);
                if (!"user".equals(message.role())) {
                    continue;
                }
                if (snapshotAt < 0 && message.content().startsWith("## 仓库现状")) {
                    snapshotAt = i;
                }
                if (inputAt < 0 && "原任务".equals(message.content())) {
                    inputAt = i;
                }
            }
            assertTrue(inputAt >= 0, "用户输入应在快照之后");
            assertTrue(snapshotAt >= 0 && snapshotAt < inputAt, "快照应位于首条用户输入之前");

            List<Message> second = requests.get(1);
            assertEquals(1, snapshotCount(second), "第二轮 run 不应重复注入快照");
            assertTrue(second.stream().anyMatch(message -> "user".equals(message.role())
                    && "第二个任务".equals(message.content())));
        }
    }

    /** 请求里「## 仓库现状」开头的 user 消息数。 */
    private static long snapshotCount(List<Message> messages) {
        return messages.stream()
                .filter(message -> "user".equals(message.role()))
                .filter(message -> message.content().startsWith("## 仓库现状"))
                .count();
    }

    @Test
    void keepsQueueWhenStepLimitPausesTask() throws Exception {
        assertKeepsQueue(1, List.of(), AgentRunServiceTest::readFile, "MAX_STEPS");
    }

    @Test
    void keepsQueueWhenModelStopsWithAnOpenPlan() throws Exception {
        assertKeepsQueue(8, List.of(new PlanItem("还没做完的原任务", PlanItem.PENDING)),
                AgentRunServiceTest::done, "INTERRUPTED");
    }

    @Test
    void waitsAcrossAutomaticContinuationBeforeStartingQueuedMessage() throws Exception {
        CountDownLatch enteredFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch enteredContinuation = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<List<Message>>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            if (tools.isEmpty() && messages.getLast().content().startsWith("[系统收尾要求]")) {
                return new ModelResponse("本轮达到步数边界，进度已保存。", List.of());
            }
            return switch (calls.getAndIncrement()) {
                case 0 -> {
                    enteredFirst.countDown();
                    await(releaseFirst);
                    yield readFile();
                }
                case 1 -> {
                    enteredContinuation.countDown();
                    await(releaseContinuation);
                    yield done();
                }
                default -> done();
            };
        };
        try (Fixture fixture = new Fixture(model, 1)) {
            fixture.plans.save(fixture.sessionId, List.of(new PlanItem("原任务计划", PlanItem.PENDING)));
            fixture.start();
            await(enteredFirst);
            assertTrue(fixture.service.followup(fixture.sessionId, "下一条"));
            releaseFirst.countDown();
            await(enteredContinuation);
            assertEquals(List.of("下一条"), fixture.queue());
            assertEquals(List.of("原任务"), userInputs(requests.get(2)));
            fixture.plans.save(fixture.sessionId, List.of(new PlanItem("原任务计划", PlanItem.COMPLETED)));
            releaseContinuation.countDown();
            fixture.awaitFinished("MAX_STEPS", "COMPLETED", "COMPLETED");
            assertEquals(List.of(), fixture.queue());
            assertEquals(List.of("原任务", "下一条"), userInputs(requests.get(3)));
        } finally {
            releaseFirst.countDown();
            releaseContinuation.countDown();
        }
    }

    @Test
    void goalStepLimitResumesWithoutBudgetReviewer() throws Exception {
        AtomicInteger executionCalls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (tools.isEmpty() && messages.getLast().content().startsWith("[系统收尾要求]")) {
                return new ModelResponse("进度已保存", List.of());
            }
            int call = executionCalls.getAndIncrement();
            if (call < 4) return new ModelResponse("", List.of(new ToolCall("read" + call, "read_file",
                    Map.of("path", "A" + call + ".java"))));
            return done();
        };
        try (Fixture fixture = new Fixture(model, 4, true)) {
            fixture.start();
            fixture.awaitFinished("MAX_STEPS");
            assertEquals(4, executionCalls.get());
            TaskControlService tasks = new TaskControlService(JsonMapper.builder().build(),
                    new PilotDataDir(dataRoot.toString()));
            assertEquals("BUDGET_LIMITED", tasks.load(fixture.sessionId).status());

            String resumeRun = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.sessions.publish(fixture.sessionId, resumeRun, new RunStarted("（继续上一次未完成的任务）"));
            fixture.service.resume(fixture.sessionId, resumeRun);
            fixture.awaitFinished("COMPLETED");
            assertEquals(5, executionCalls.get());
            assertEquals(1, tasks.load(fixture.sessionId).execution().budgetSegments());
        }
    }

    @Test
    void unfinishedGoalPlanContinuesAfterStepLimitWithoutAnotherDecision() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture[] current = new Fixture[1];
        ModelClient model = (messages, tools) -> {
            if (tools.isEmpty() && messages.getLast().content().startsWith("[系统收尾要求]")) {
                return new ModelResponse("进度已保存", List.of());
            }
            if (calls.getAndIncrement() == 0) return readFile();
            current[0].plans.save(current[0].sessionId,
                    List.of(new PlanItem("原任务计划", PlanItem.COMPLETED)));
            return done();
        };
        try (Fixture fixture = new Fixture(model, 1, true)) {
            current[0] = fixture;
            fixture.plans.save(fixture.sessionId, List.of(new PlanItem("原任务计划", PlanItem.PENDING)));
            fixture.start();
            fixture.awaitFinished("MAX_STEPS", "COMPLETED");
            assertEquals(2, calls.get());
            TaskControlService tasks = new TaskControlService(JsonMapper.builder().build(),
                    new PilotDataDir(dataRoot.toString()));
            assertEquals(1, tasks.load(fixture.sessionId).execution().budgetSegments());
        }
    }

    @Test
    void keepsQueueWhenUserCancelsTask() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelClient model = (messages, tools) -> {
            entered.countDown();
            await(release);
            return readFile();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.followup(fixture.sessionId, "下一条"));
            assertTrue(fixture.service.cancel(fixture.runId));
            fixture.awaitFinished("CANCELLED");
            assertEquals(List.of("下一条"), fixture.queue());
            assertEquals(1, fixture.sessions.events(fixture.sessionId).stream()
                    .filter(event -> event.payload() instanceof RunStarted).count());
        } finally {
            release.countDown();
        }
    }

    @Test
    void keepsQueueWhenStartingNextRunFails() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelClient model = (messages, tools) -> {
            entered.countDown();
            await(release);
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.followup(fixture.sessionId, "下一条"));
            // 删除目标会话模拟 run.started 发布失败；排队内容不应先被移除。
            fixture.sessions.delete(fixture.sessionId);
            release.countDown();
            fixture.awaitFinished("COMPLETED");
            assertEquals(List.of("下一条"), fixture.queue());
            assertTrue(fixture.service.activeRun(fixture.sessionId).isEmpty());
        } finally {
            release.countDown();
        }
    }

    private void assertKeepsQueue(int maxSteps, List<PlanItem> plan,
                                 java.util.function.Supplier<ModelResponse> response, String status) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
                return response.get();
            }
            return done();
        };
        try (Fixture fixture = new Fixture(model, maxSteps)) {
            fixture.plans.save(fixture.sessionId, plan);
            fixture.start();
            await(entered);
            assertTrue(fixture.service.followup(fixture.sessionId, "下一条"));
            release.countDown();
            fixture.awaitFinished(status);
            assertEquals(List.of("下一条"), fixture.queue());
            assertEquals("INTERRUPTED".equals(status) ? 1 : 2, calls.get(),
                    "真正停止时应额外执行一次禁用工具的收尾请求");
            assertTrue(fixture.service.activeRun(fixture.sessionId).isEmpty());
        } finally {
            release.countDown();
        }
    }

    private class Fixture implements AutoCloseable {
        final SessionService sessions = new SessionService();
        final InMemoryPlanRepository plans = new InMemoryPlanRepository();
        final BlockingQueue<String> finished = new LinkedBlockingQueue<>();
        final String sessionId = sessions.createSession("test", workspace.toString()).id();
        final RunMetrics metrics;
        final AgentRunService service;
        final com.pilot.core.tool.CommandTaskManager commandTasks;
        String runId;

        Fixture(ModelClient model, int maxSteps) {
            this(model, maxSteps, false);
        }

        Fixture(ModelClient model, int maxSteps, boolean goals) {
            AgentProperties properties = new AgentProperties(workspace.toString(), PermissionMode.READ_ONLY,
                    maxSteps, 3, 5, 300, 1, 0, 60_000, false, goals,
                    null, null, null, null, null, null, null, null);
            JsonMapper mapper = JsonMapper.builder().build();
            PilotDataDir dataDir = new PilotDataDir(dataRoot.toString());
            commandTasks = new com.pilot.core.tool.CommandTaskManager(dataDir.file("commands"), mapper);
            WorkspaceToolFactory factory = mock(WorkspaceToolFactory.class, withSettings().mockMaker(SUBCLASS));
            when(factory.resolve(anyString())).thenReturn(workspace);
            when(factory.registryFor(anyString(), any(PlanSink.class))).thenAnswer(invocation -> {
                ToolRegistry tools = new ToolRegistry();
                tools.register(new ReadFileTool(new PathValidator(workspace)));
                if (goals) tools.register(new com.pilot.core.tool.Tool() {
                    @Override public String name() { return "edit_file"; }
                    @Override public String description() { return "test edit"; }
                    @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
                    @Override public boolean readOnly() { return true; }
                    @Override public String execute(Map<String, Object> args) { return "updated"; }
                    @Override public com.pilot.core.tool.ToolResult executeResult(Map<String, Object> args) {
                        return new com.pilot.core.tool.ToolResult("updated", List.of(String.valueOf(args.get("path"))), List.of());
                    }
                });
                return tools;
            });
            metrics = new RunMetrics() {
                @Override
                public void recordRun(String status, long durationMs) {
                    super.recordRun(status, durationMs);
                    finished.add(status);
                }
            };
            service = new AgentRunService(sessions, model, factory,
                    new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                    properties, new ModelSettings(new ModelProperties("", "http://localhost", "test", List.of()),
                            new InMemorySettingsStore()), new RunSnapshotService(mapper, dataDir),
                    new RunRegistry(), RunRepository.noop(), new InMemoryConversationRepository(),
                    plans, mapper, dataDir, new QueuedMessageRepository(), null,
                    new MultiAgentProperties(false, 1, 1, 8, 30, List.of()), metrics, new TaskControlService(mapper, dataDir),
                    commandTasks);
        }

        void start() {
            runId = service.claimRun(sessionId).orElseThrow();
            sessions.publish(sessionId, runId, new RunStarted("原任务"));
            service.start(sessionId, runId, "原任务");
        }

        List<String> queue() {
            return service.queued(sessionId);
        }

        void awaitFinished(String... statuses) throws InterruptedException {
            for (String status : statuses) {
                assertEquals(status, finished.poll(5, TimeUnit.SECONDS));
            }
        }

        @Override
        public void close() {
            service.shutdown();
            commandTasks.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "worker did not reach expected boundary");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 用户输入序列：过滤会话首条自动注入的仓库快照（以「## 仓库现状」开头的 user 消息）。 */
    private static List<String> userInputs(List<Message> messages) {
        return messages.stream().filter(message -> "user".equals(message.role()))
                .map(Message::content)
                .filter(content -> !content.startsWith("## 仓库现状"))
                .toList();
    }

    private static ModelResponse readFile() {
        return new ModelResponse("原任务还在进行", List.of(new ToolCall("call_1", "read_file", Map.of("path", "a.txt"))));
    }

    private static ModelResponse done() {
        return new ModelResponse("完成", List.of());
    }
}
