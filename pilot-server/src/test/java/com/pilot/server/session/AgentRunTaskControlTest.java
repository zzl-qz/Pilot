package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunSteered;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.session.InMemoryConversationRepository;
import com.pilot.core.session.InMemoryPlanRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.Tool;
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
import static org.mockito.Mockito.*;

/** 跨 HTTP 之外的真实运行服务测试，验证输入路由、持久目标和循环边界一起工作。 */
class AgentRunTaskControlTest {
    @TempDir Path workspace;
    @TempDir Path dataRoot;

    @Test
    void followupCreatesIndependentTaskWhileSteerChangesTheCurrentRun() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var correctedEntered = new CountDownLatch(1);
        var correctedRelease = new CountDownLatch(1);
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(List.copyOf(messages));
            return switch (calls.getAndIncrement()) {
                case 0 -> {
                    firstEntered.countDown();
                    await(firstRelease);
                    yield new ModelResponse("旧方向", List.of(new ToolCall("old", "read_file", Map.of("path", "obsolete.txt"))));
                }
                case 1 -> {
                    correctedEntered.countDown();
                    await(correctedRelease);
                    yield done();
                }
                default -> done();
            };
        };
        try (var f = new Fixture(model, null)) {
            f.start(new TaskControlService.Requirements(List.of("只改重连逻辑"), List.of()));
            await(firstEntered);
            String taskId = f.tasks.load(f.id).taskId();
            assertTrue(f.service.followup(f.id, "下一件事：整理文档"));
            assertFalse(f.service.steer(f.id, "run_previous", "过期纠正"), "旧运行的纠正不得进入新任务");
            assertTrue(f.service.steer(f.id, "立即改查服务端重启后的重连"));
            assertEquals(List.of("下一件事：整理文档"), f.service.queued(f.id));
            assertEquals("修复断线重连", f.tasks.load(f.id).originalGoal());
            assertEquals(taskId, f.tasks.load(f.id).taskId());
            firstRelease.countDown();
            await(correctedEntered);
            var currentInput = userInputs(requests.get(1));
            assertTrue(currentInput.contains("立即改查服务端重启后的重连"));
            assertFalse(currentInput.contains("下一件事：整理文档"));
            assertTrue(requests.get(1).getFirst().content().contains("只改重连逻辑"));
            assertTrue(requests.get(1).getFirst().content().contains("立即改查服务端重启后的重连"));
            correctedRelease.countDown();
            f.finished("COMPLETED", "COMPLETED");
            var events = f.sessions.events(f.id);
            assertEquals(2, events.stream().filter(e -> e.payload() instanceof RunStarted).count());
            var steer = events.stream().filter(e -> e.payload() instanceof RunSteered).findFirst().orElseThrow();
            assertEquals(f.firstRun, steer.runId(), "立即纠正必须留在当前运行内");
            assertFalse(events.stream().anyMatch(e -> "tool.started".equals(e.type())), "返回的旧方案工具不能继续执行");
            var next = f.tasks.load(f.id);
            assertNotEquals(taskId, next.taskId());
            assertEquals("下一件事：整理文档", next.originalGoal());
            assertTrue(next.constraints().isEmpty(), "下一件独立任务不能继承上一件的范围约束");
            assertTrue(next.corrections().isEmpty());
            assertTrue(f.service.queued(f.id).isEmpty());
        } finally {
            firstRelease.countDown();
            correctedRelease.countDown();
        }
    }

    @Test
    void pauseWaitsForCurrentToolAndResumeReinjectsOriginalRequirements() throws Exception {
        var toolEntered = new CountDownLatch(1);
        var toolRelease = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var calls = new AtomicInteger();
        Tool blocking = new Tool() {
            public String name() { return "inspect_connection"; }
            public String description() { return "读取连接状态"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public boolean readOnly() { return true; }
            public String execute(Map<String, Object> args) {
                executions.incrementAndGet();
                toolEntered.countDown();
                await(toolRelease);
                return "连接状态已记录";
            }
        };
        ModelClient model = (messages, tools) -> {
            requests.add(List.copyOf(messages));
            return calls.getAndIncrement() == 0
                    ? new ModelResponse("检查连接", List.of(new ToolCall("inspect", "inspect_connection", Map.of())))
                    : done();
        };
        try (var f = new Fixture(model, blocking)) {
            var requirements = new TaskControlService.Requirements(List.of("不得修改数据库"),
                    List.of(new TaskControlService.Acceptance("断线后重连测试通过", false, "")));
            f.start(requirements);
            await(toolEntered);
            var original = f.tasks.load(f.id);
            assertTrue(f.service.followup(f.id, "下一件事"));
            assertTrue(f.service.pause(f.firstRun));
            assertFalse(f.service.steer(f.id, "暂停后不能再插入"));
            assertTrue(f.service.activeRun(f.id).isPresent(), "正在执行的工具应在安全边界结束，不能提前释放工作区");
            assertFalse(f.sessions.events(f.id).stream().anyMatch(e -> e.payload() instanceof RunCompleted));
            toolRelease.countDown();
            f.finished("PAUSED");
            var paused = f.tasks.load(f.id);
            assertEquals("PAUSED", paused.status());
            assertEquals(original.taskId(), paused.taskId());
            assertEquals(original.constraints(), paused.constraints());
            assertEquals(original.acceptance(), paused.acceptance());
            assertEquals(List.of("下一件事"), f.service.queued(f.id));
            assertEquals(1, executions.get());
            assertTrue(f.sessions.events(f.id).stream().anyMatch(e -> e.payload() instanceof RunFailed failure
                    && "PAUSED".equals(failure.code()) && failure.retryable()));
            assertTrue(f.conversations.load(f.id).stream().anyMatch(m -> "tool".equals(m.role())
                    && m.content().equals("连接状态已记录")), "续跑必须能看到已经完成的工具结果");

            String resumed = f.service.claimRun(f.id).orElseThrow();
            f.sessions.publish(f.id, resumed, new RunStarted("（继续上一次未完成的任务）"));
            f.service.resume(f.id, resumed);
            f.finished("AWAITING_REVIEW");
            assertEquals(original.taskId(), f.tasks.load(f.id).taskId());
            assertEquals("修复断线重连", f.tasks.load(f.id).originalGoal());
            String resumeContext = requests.getLast().getFirst().content();
            assertTrue(resumeContext.contains("修复断线重连"));
            assertTrue(resumeContext.contains("不得修改数据库"));
            assertTrue(resumeContext.contains("断线后重连测试通过"));
            assertEquals(List.of("下一件事"), f.service.queued(f.id));
            assertEquals(2, calls.get(), "未验收时不能自动开始下一件事");
            assertTrue(f.service.activeRun(f.id).isEmpty());
        } finally { toolRelease.countDown(); }
    }

    @Test
    void completedModelOutputDoesNotDrainQueueUntilExplicitAcceptanceIsReviewed() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return done();
        };
        try (var f = new Fixture(model, null)) {
            f.start(new TaskControlService.Requirements(List.of(),
                    List.of(new TaskControlService.Acceptance("通过重连测试", false, ""))));
            await(entered);
            assertTrue(f.service.followup(f.id, "另一个独立任务"));
            release.countDown();
            f.finished("AWAITING_REVIEW");
            assertEquals("AWAITING_REVIEW", f.tasks.load(f.id).status());
            assertEquals(List.of("另一个独立任务"), f.service.queued(f.id));
            assertEquals(1, calls.get());
            var result = f.sessions.events(f.id).stream().map(e -> e.payload())
                    .filter(RunCompleted.class::isInstance).map(RunCompleted.class::cast).findFirst().orElseThrow();
            assertTrue(result.openSteps().contains("待验收：通过重连测试"));
            assertTrue(result.planOpen());
            f.tasks.update(f.id, List.of(), List.of(new TaskControlService.Acceptance("通过重连测试", true, "重连测试通过日志")));
            f.service.requirementsUpdated(f.id);
            f.finished("COMPLETED");
            assertTrue(f.service.queued(f.id).isEmpty());
            assertEquals("另一个独立任务", f.tasks.load(f.id).originalGoal());
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void regenerateAfterResumePreservesOriginalGoalAndCorrections() throws Exception {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        ModelClient model = (messages, tools) -> { requests.add(List.copyOf(messages)); return done(); };
        try (var f = new Fixture(model, null)) {
            f.start(new TaskControlService.Requirements(List.of("不得修改数据库"), List.of()));
            f.finished("COMPLETED");
            var original = f.tasks.correct(f.id, "优先检查服务端重启后的重连");
            var resumed = f.service.claimRun(f.id).orElseThrow();
            f.sessions.publish(f.id, resumed, new RunStarted("（继续上一次未完成的任务）"));
            f.service.resume(f.id, resumed);
            f.finished("COMPLETED");
            var regenerated = f.service.claimRun(f.id).orElseThrow();
            f.service.regenerate(f.id, regenerated, "（继续上一次未完成的任务）");
            f.finished("COMPLETED");
            var task = f.tasks.load(f.id);
            assertEquals(original.taskId(), task.taskId());
            assertEquals("修复断线重连", task.originalGoal());
            assertEquals(original.corrections(), task.corrections());
            assertEquals(List.of("修复断线重连"), userInputs(requests.getLast()));
            assertTrue(requests.getLast().getFirst().content().contains("优先检查服务端重启后的重连"));
        }
    }

    @Test
    void confirmedAcceptanceWakesQueuedTaskWithNewGoal() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
            }
            return done();
        };
        try (var f = new Fixture(model, null)) {
            f.start(new TaskControlService.Requirements(List.of(),
                    List.of(new TaskControlService.Acceptance("重连测试通过", false, ""))));
            await(entered);
            assertTrue(f.service.followup(f.id, "验收后继续整理文档"));
            release.countDown();
            f.finished("AWAITING_REVIEW");
            assertEquals(1, calls.get());
            f.tasks.update(f.id, List.of(), List.of(
                    new TaskControlService.Acceptance("重连测试通过", true, "ReconnectTest 全部通过")));
            f.service.requirementsUpdated(f.id);
            f.finished("COMPLETED");
            assertEquals(2, calls.get());
            assertEquals("验收后继续整理文档", f.tasks.load(f.id).originalGoal());
            assertTrue(f.service.queued(f.id).isEmpty());
        } finally { release.countDown(); }
    }

    private class Fixture implements AutoCloseable {
        final SessionService sessions = new SessionService();
        final InMemoryPlanRepository plans = new InMemoryPlanRepository();
        final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        final BlockingQueue<String> finished = new LinkedBlockingQueue<>();
        final String id = sessions.createSession("任务控制测试", workspace.toString()).id();
        final TaskControlService tasks;
        final AgentRunService service;
        String firstRun;

        Fixture(ModelClient model, Tool additional) {
            var properties = new AgentProperties(workspace.toString(), PermissionMode.READ_ONLY, 8, 3, 300, 1, 0, 60_000, false);
            var mapper = JsonMapper.builder().build();
            var data = new PilotDataDir(dataRoot.toString());
            tasks = new TaskControlService(mapper, data);
            var factory = mock(WorkspaceToolFactory.class, withSettings().mockMaker(SUBCLASS));
            when(factory.resolve(anyString())).thenReturn(workspace);
            when(factory.registryFor(anyString(), any(PlanSink.class))).thenAnswer(invocation -> {
                var tools = new ToolRegistry();
                tools.register(new ReadFileTool(new PathValidator(workspace)));
                if (additional != null) tools.register(additional);
                return tools;
            });
            var metrics = new RunMetrics() {
                public void recordRun(String status, long durationMs) {
                    super.recordRun(status, durationMs);
                    finished.add(status);
                }
            };
            service = new AgentRunService(sessions, model, factory,
                    new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(), properties,
                    new ModelSettings(new ModelProperties("", "http://localhost", "test", List.of()), new InMemorySettingsStore()),
                    new RunSnapshotService(mapper, data), new RunRegistry(), RunRepository.noop(), conversations, plans,
                    mapper, data, new QueuedMessageRepository(), null,
                    new MultiAgentProperties(false, 1, 1, 8, 30, List.of()), metrics, tasks);
        }

        void start(TaskControlService.Requirements requirements) {
            firstRun = service.claimRun(id).orElseThrow();
            sessions.publish(id, firstRun, new RunStarted("修复断线重连"));
            service.start(id, firstRun, "修复断线重连", List.of(), requirements);
        }
        void finished(String... statuses) throws InterruptedException {
            for (String status : statuses) assertEquals(status, finished.poll(8, TimeUnit.SECONDS));
        }
        public void close() { service.shutdown(); }
    }

    private static List<String> userInputs(List<Message> messages) {
        return messages.stream().filter(message -> "user".equals(message.role())).map(Message::content).toList();
    }
    private static ModelResponse done() { return new ModelResponse("检查完成", List.of()); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(8, TimeUnit.SECONDS), "expected run boundary was not reached"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
