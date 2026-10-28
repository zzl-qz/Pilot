package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunStarted;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.MockMakers.SUBCLASS;

class AgentRunServiceTest {
    @TempDir
    Path workspace;
    @TempDir
    Path dataRoot;

    @Test
    void waitsForTaskCompletionThenStartsEditedMessagesInOrder() throws Exception {
        var enteredFirst = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var enteredSecond = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
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
        try (var fixture = new Fixture(model, 8)) {
            fixture.start();
            await(enteredFirst);
            assertTrue(fixture.service.steer(fixture.sessionId, "下一条"));
            assertTrue(fixture.service.steer(fixture.sessionId, "再下一条"));
            assertTrue(fixture.service.steer(fixture.sessionId, "删除这条"));
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
            var events = fixture.sessions.events(fixture.sessionId);
            assertFalse(events.stream().anyMatch(event -> event.type().equals("run.steered")));
            var firstCompleted = events.stream().filter(event -> event.payload() instanceof RunCompleted)
                    .findFirst().orElseThrow();
            var nextStarted = events.stream().filter(event -> event.payload() instanceof RunStarted started
                    && started.input().equals("修改后的下一条")).findFirst().orElseThrow();
            assertTrue(firstCompleted.sequence() < nextStarted.sequence());
            assertNotEquals(firstCompleted.runId(), nextStarted.runId());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @Test
    void keepsQueueWhenModelFails() throws Exception {
        assertKeepsQueue(8, List.of(), () -> { throw new IllegalArgumentException("invalid model request"); }, "FAILED");
    }

    @Test
    void injectsRepositoryBriefOncePerSession() throws Exception {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            return done();
        };
        Files.writeString(workspace.resolve("a.txt"), "evidence");
        try (var fixture = new Fixture(model, 8)) {
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
                AgentRunServiceTest::done, "COMPLETED");
    }

    @Test
    void waitsAcrossAutomaticContinuationBeforeStartingQueuedMessage() throws Exception {
        var enteredFirst = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var enteredContinuation = new CountDownLatch(1);
        var releaseContinuation = new CountDownLatch(1);
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
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
        try (var fixture = new Fixture(model, 1)) {
            fixture.plans.save(fixture.sessionId, List.of(new PlanItem("原任务计划", PlanItem.PENDING)));
            fixture.start();
            await(enteredFirst);
            assertTrue(fixture.service.steer(fixture.sessionId, "下一条"));
            releaseFirst.countDown();
            await(enteredContinuation);
            assertEquals(List.of("下一条"), fixture.queue());
            assertEquals(List.of("原任务"), userInputs(requests.get(1)));
            fixture.plans.save(fixture.sessionId, List.of(new PlanItem("原任务计划", PlanItem.COMPLETED)));
            releaseContinuation.countDown();
            fixture.awaitFinished("MAX_STEPS", "COMPLETED", "COMPLETED");
            assertEquals(List.of(), fixture.queue());
            assertEquals(List.of("原任务", "下一条"), userInputs(requests.get(2)));
        } finally {
            releaseFirst.countDown();
            releaseContinuation.countDown();
        }
    }

    @Test
    void keepsQueueWhenUserCancelsTask() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ModelClient model = (messages, tools) -> {
            entered.countDown();
            await(release);
            return readFile();
        };
        try (var fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.steer(fixture.sessionId, "下一条"));
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
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ModelClient model = (messages, tools) -> {
            entered.countDown();
            await(release);
            return done();
        };
        try (var fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.steer(fixture.sessionId, "下一条"));
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
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
                return response.get();
            }
            return done();
        };
        try (var fixture = new Fixture(model, maxSteps)) {
            fixture.plans.save(fixture.sessionId, plan);
            fixture.start();
            await(entered);
            assertTrue(fixture.service.steer(fixture.sessionId, "下一条"));
            release.countDown();
            fixture.awaitFinished(status);
            assertEquals(List.of("下一条"), fixture.queue());
            assertEquals(1, calls.get());
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
        final AgentRunService service;
        String runId;

        Fixture(ModelClient model, int maxSteps) {
            var properties = new AgentProperties(workspace.toString(), PermissionMode.READ_ONLY,
                    maxSteps, 3, 5, 300, 1, 0, 60_000, false);
            var mapper = JsonMapper.builder().build();
            var dataDir = new PilotDataDir(dataRoot.toString());
            var factory = mock(WorkspaceToolFactory.class, withSettings().mockMaker(SUBCLASS));
            when(factory.resolve(anyString())).thenReturn(workspace);
            when(factory.registryFor(anyString(), any(PlanSink.class))).thenAnswer(invocation -> {
                var tools = new ToolRegistry();
                tools.register(new ReadFileTool(new PathValidator(workspace)));
                return tools;
            });
            var metrics = new RunMetrics() {
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
                    new MultiAgentProperties(false, 1, 1, 8, 30, List.of()), metrics);
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
