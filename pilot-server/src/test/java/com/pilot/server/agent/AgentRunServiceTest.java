package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.MockMakers.SUBCLASS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.model.diagnostics.ModelRequestScope;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.session.InMemoryConversationRepository;
import com.pilot.core.session.InMemoryPlanRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UpdatePlanTool;
import com.pilot.core.tool.code.CodeSymbolTool;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.WindowsJdkLoopbackCondition;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.SkillProperties;
import com.pilot.server.config.WebSearchProperties;
import com.pilot.server.model.ModelProperties;
import com.pilot.server.model.ModelSettings;
import com.pilot.server.observability.RunMetrics;
import com.pilot.server.run.RunRegistry;
import com.pilot.server.run.RunStarter;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.skill.SkillInstallService;
import com.pilot.server.skill.SkillService;
import com.pilot.server.store.InMemorySettingsStore;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.store.QueuedMessageRepository;
import com.pilot.server.tool.WorkspaceToolFactory;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.json.JsonMapper;

class AgentRunServiceTest {
    @TempDir
    Path workspace;
    @TempDir
    Path dataRoot;

    @Test
    void reasoningResetOnRetryStaysLiveAndNeverEntersHistory() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                throw new AssertionError("必须使用流式模型入口");
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text,
                Consumer<String> reasoning) {
                reasoning.accept("推理片段");
                text.accept("临时正文");
                if (attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("HTTP 503 Service Unavailable");
                }
                return done();
            }
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            var resetDelivered = new CountDownLatch(1);
            try (var subscription = fixture.sessions.subscribe(fixture.sessionId, 0, event -> {
                if (event.payload() instanceof AssistantReasoning reasoning && reasoning.reset()) {
                    resetDelivered.countDown();
                }
            })) {
                fixture.start();
                fixture.awaitFinished("COMPLETED");
                await(resetDelivered);
                assertEquals(2, attempts.get());
                List<EventEnvelope> history = fixture.sessions.events(fixture.sessionId);
                assertTrue(history.stream().anyMatch(event -> event.type().equals("run.retrying")));
                assertFalse(history.stream().anyMatch(event -> event.payload() instanceof AssistantReasoning
                    || event.payload() instanceof AssistantPreview), "重试时的临时片段和重置都不能落库");
                assertFalse(subscription.isClosed(), subscription.closeReason());
            }
        }
    }

    @Test
    void runsTwoSessionsInTheSameWorkspaceConcurrently() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ModelClient model = (messages, tools) -> {
            bothEntered.countDown();
            await(release);
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8, false, 2)) {
            String second = fixture.sessions.createSession("第二个任务", workspace.toString()).id();
            fixture.start();
            String secondRun = fixture.service.claimRun(second).orElseThrow();
            fixture.sessions.publish(second, secondRun, new RunStarted("第二个任务"));
            fixture.service.start(second, secondRun, "第二个任务");
            await(bothEntered);
            assertEquals(fixture.runId, fixture.service.activeRun(fixture.sessionId).orElseThrow());
            assertEquals(secondRun, fixture.service.activeRun(second).orElseThrow());
            assertTrue(fixture.service.claimRun(fixture.sessionId).isEmpty(), "同会话仍只能有一个活动运行");
            release.countDown();
            fixture.awaitFinished("COMPLETED", "COMPLETED");
            assertTrue(fixture.sessions.events(fixture.sessionId).stream()
                .allMatch(event -> event.runId().equals(fixture.runId)));
            assertTrue(fixture.sessions.events(second).stream().allMatch(event -> event.runId().equals(secondRun)));
        } finally {
            release.countDown();
        }
    }

    @Test
    void aggregatesChildUsageWithoutReplacingMainRunSnapshot() {
        AgentRunService.RunUsageAccumulator usage = new AgentRunService.RunUsageAccumulator();

        RunUsageUpdated main = usage.main(new RunUsageUpdated(2, 1_000, 100, 700, 8_000, true));
        RunUsageUpdated child = usage.child(new ModelUsage(300, 30, 0, true));
        RunUsageUpdated nextMain = usage.main(new RunUsageUpdated(3, 1_500, 150, 900, 8_000, true));
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
    void accumulatesChildCachedTokensIntoRunSnapshot() {
        AgentRunService.RunUsageAccumulator usage = new AgentRunService.RunUsageAccumulator();

        RunUsageUpdated main = usage.main(new RunUsageUpdated(2, 1_000, 100, 400, 8_000, 120_000, true));
        RunUsageUpdated withChild = usage.child(new ModelUsage(300, 30, 0, 250, 0, true));

        assertEquals(400, main.cachedInputTokens());
        assertEquals(1_300, withChild.inputTokens());
        assertEquals(650, withChild.cachedInputTokens(), "子代理的缓存命中必须并入 run 级快照");
        assertEquals(8_000, withChild.contextTokens(), "上下文水位始终来自主请求");
    }

    @Test
    void keepsCachedTokensWhenChildUsageIsUnknown() {
        AgentRunService.RunUsageAccumulator usage = new AgentRunService.RunUsageAccumulator();

        usage.main(new RunUsageUpdated(1, 500, 50, 200, 4_000, 120_000, true));
        RunUsageUpdated merged = usage.child(ModelUsage.unknown());

        assertEquals(200, merged.cachedInputTokens(), "未知的用量没有缓存观测，不该冲掉已有命中数");
        assertFalse(merged.exact());
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
            EventEnvelope firstCompleted =
                events.stream().filter(event -> event.payload() instanceof RunCompleted).findFirst().orElseThrow();
            EventEnvelope nextStarted = events.stream()
                .filter(event -> event.payload() instanceof RunStarted started && started.input().equals("修改后的下一条"))
                .findFirst().orElseThrow();
            assertTrue(firstCompleted.sequence() < nextStarted.sequence());
            assertNotEquals(firstCompleted.runId(), nextStarted.runId());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @Test
    void heldQueueWaitsForResumeAndOnlyStartsHeadOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
            }
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.followup(fixture.sessionId, "下一条"));
            assertTrue(fixture.service.setQueueHeld(fixture.sessionId, true));
            assertEquals("队列已暂停，恢复后才会开始下一条", fixture.service.queueWaitingReason(fixture.sessionId));

            // 暂停与当前任务结束同时发生：只结束当前轮，队列保留
            release.countDown();
            fixture.awaitFinished("COMPLETED");
            assertEquals(List.of("下一条"), fixture.queue());
            assertTrue(fixture.service.activeRun(fixture.sessionId).isEmpty());
            assertTrue(fixture.service.setQueueHeld(fixture.sessionId, true), "重复暂停保持幂等");

            // 恢复：空闲且验收完成，只启动队首一次
            assertTrue(fixture.service.setQueueHeld(fixture.sessionId, false));
            fixture.awaitFinished("COMPLETED");
            assertEquals(List.of(), fixture.queue());
            // 重复恢复不会再多开一轮
            assertTrue(fixture.service.setQueueHeld(fixture.sessionId, true));
            assertTrue(fixture.service.setQueueHeld(fixture.sessionId, false));
            fixture.awaitFinished();
            assertEquals(List.of(), fixture.queue());
            assertEquals(2, calls.get(), "只有首条排队消息被启动一次");
        } finally {
            release.countDown();
        }
    }

    @Test
    void queuedRequirementsBecomeTheNextTaskContract() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
            }
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.followup(fixture.sessionId, "下一条", List.of(), List.of(),
                new RunStarter.QueueRequirements(List.of("保留 API"), List.of("覆盖失败路径"))));
            release.countDown();
            fixture.awaitFinished("COMPLETED", "AWAITING_REVIEW");

            var tasks = new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(dataRoot.toString()));
            var contract = tasks.load(fixture.sessionId);
            assertEquals(List.of("保留 API"), contract.constraints());
            assertEquals(List.of("覆盖失败路径"),
                contract.acceptance().stream().map(TaskControlService.Acceptance::text).toList());
        } finally {
            release.countDown();
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
                .filter(message -> "user".equals(message.role()) && "改成这样看图".equals(message.content())).findFirst()
                .orElseThrow();
            assertEquals(List.of(image), delivered.attachments());
            assertEquals(List.of(2), delivered.imagePositions());
            RunStarted started = fixture.sessions.events(fixture.sessionId).stream().map(event -> event.payload())
                .filter(payload -> payload instanceof RunStarted run && "改成这样看图".equals(run.input()))
                .map(payload -> (RunStarted)payload).findFirst().orElseThrow();
            assertEquals(List.of(image), started.attachments());
            assertEquals(List.of(), fixture.queue());
        } finally {
            release.countDown();
        }
    }

    /**
     * 普通 run 必须真的把 spawn_agent 声明给模型：工具注册发生在提示词组装之后，
     * 任何一侧漏改都会让「子代理从没触发」变成默认状态。
     */
    @Test
    void normalRunsExposeSpawnAgentAndItsGuidance() throws Exception {
        CopyOnWriteArrayList<List<ToolDefinition>> toolSets = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            toolSets.add(tools);
            return done();
        };

        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            List<String> names = toolSets.getFirst().stream().map(ToolDefinition::name).toList();
            assertTrue(names.contains("spawn_agent"), "普通 run 应向模型暴露 spawn_agent：" + names);
            String system = requests.getFirst().getFirst().content();
            assertTrue(system.contains("子代理调研"), "工具可见时系统提示词应带子代理使用场景");
        }
    }

    @Test
    void reviewRunsUseReadOnlyToolsAndInjectTheProtocol() throws Exception {
        CopyOnWriteArrayList<List<ToolDefinition>> toolSets = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            toolSets.add(tools);
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8, true)) {
            fixture.runId = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.sessions.publish(fixture.sessionId, fixture.runId, new RunStarted("/review 重点看登录模块"));
            fixture.service.start(fixture.sessionId, fixture.runId, "/review 重点看登录模块");

            fixture.awaitFinished("COMPLETED");

            // 审查 run 的工具表：读工具在，写工具与派发工具不存在（goals fixture 里 edit_file 也只给普通 run）
            List<String> names = toolSets.getFirst().stream().map(ToolDefinition::name).toList();
            assertTrue(names.contains("read_file"), "审查需要读取工具：" + names);
            assertFalse(names.contains("edit_file"), "审查 run 不提供写工具：" + names);
            assertFalse(names.contains("spawn_agent"), "审查 run 不派子代理：" + names);
            // 系统提示词注入固定审查协议与范围说明，且输入原文保留在会话历史里
            String system = requests.getFirst().getFirst().content();
            assertTrue(system.contains("审查协议"), "系统提示词缺审查协议：" + system.substring(0, 120));
            assertTrue(system.contains("重点看登录模块"), "协议范围必须带上用户指定的审查范围");
            assertFalse(system.contains("子代理调研"), "审查 run 没有派发工具，提示词不应介绍子代理");
            assertTrue(fixture.sessions.events(fixture.sessionId).stream().anyMatch(
                event -> event.payload() instanceof RunStarted started && "/review 重点看登录模块".equals(started.input())));
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
            for (String event : List.of("event.tool.started", "event.tool.completed", "event.assistant.delta",
                "event.run.completed")) {
                assertEquals(1L, metrics.counters().getOrDefault(event, 0L).longValue(), event);
            }
            List<EventEnvelope> history = fixture.sessions.events(fixture.sessionId);
            assertFalse(history.stream().anyMatch(event -> event.payload() instanceof AssistantPreview));
            assertEquals(List.of("完成"),
                history.stream().map(EventEnvelope::payload).filter(AssistantDelta.class::isInstance)
                    .map(AssistantDelta.class::cast).map(AssistantDelta::text).toList(),
                "工具调用前的临时预览不应作为正文落库");
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
        assertKeepsQueue(8, List.of(), () -> {
            throw new IllegalArgumentException("invalid model request");
        }, "FAILED");
    }

    @Test
    void failedRunKeepsQueueThenContinueSendsItInOrder() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> inputs = new CopyOnWriteArrayList<>();
        ModelClient model = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
                throw new IllegalArgumentException("invalid model request");
            }
            inputs.add(messages.getLast().content());
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            await(entered);
            assertTrue(fixture.service.followup(fixture.sessionId, "先发这条"));
            assertTrue(fixture.service.followup(fixture.sessionId, "再发这条"));
            release.countDown();
            fixture.awaitFinished("FAILED");
            assertEquals(List.of("先发这条", "再发这条"), fixture.queue());

            String runId = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.service.resume(fixture.sessionId, runId);
            fixture.awaitFinished("COMPLETED", "COMPLETED", "COMPLETED");

            assertEquals(List.of(), fixture.queue());
            int first = -1;
            int second = -1;
            for (int index = 0; index < inputs.size(); index++) {
                if (first < 0 && inputs.get(index).contains("先发这条")) {
                    first = index;
                }
                if (inputs.get(index).contains("再发这条")) {
                    second = index;
                }
            }
            assertTrue(first >= 0 && second > first, inputs.toString());
        } finally {
            release.countDown();
        }
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

            // 第二次 run：工作区指纹没变，不应重复注入
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
            assertEquals(1, snapshotCount(second), "指纹没变时第二轮 run 不应重复注入快照");
            assertTrue(second.stream()
                .anyMatch(message -> "user".equals(message.role()) && "第二个任务".equals(message.content())));
        }
    }

    /** 请求里「## 仓库现状」开头的 user 消息数。 */
    private static long snapshotCount(List<Message> messages) {
        return messages.stream().filter(message -> "user".equals(message.role()))
            .filter(message -> message.content().startsWith("## 仓库现状")).count();
    }

    /** 首条请求里的仓库快照正文；没有快照时返回 null。 */
    private static String snapshotOf(List<Message> messages) {
        return messages.stream().filter(message -> "user".equals(message.role())).map(Message::content)
            .filter(content -> content.startsWith("## 仓库现状")).findFirst().orElse(null);
    }

    @Test
    void briefModeKeepsHeadlineFactsButDropsDirectoryTree() throws Exception {
        Files.writeString(workspace.resolve("only-in-full.txt"), "evidence");
        var requests = new CopyOnWriteArrayList<List<Message>>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8, false, 1, PermissionMode.READ_ONLY, null, "brief")) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            String snapshot = snapshotOf(requests.getFirst());
            assertTrue(snapshot != null, "brief 档仍要注入快照");
            assertTrue(snapshot.contains("构建："), "brief 保留构建方式");
            assertTrue(snapshot.contains("规范检查"), "brief 保留 lint 档位");
            assertFalse(snapshot.contains("only-in-full.txt"), "brief 不带目录树：" + snapshot);
        }
    }

    @Test
    void offModeInjectsNoSnapshotAtAll() throws Exception {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8, false, 1, PermissionMode.READ_ONLY, null, "off")) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            assertEquals(0, snapshotCount(requests.getFirst()), "off 档首条 run 也不注入");
            assertTrue(requests.getFirst().stream()
                .anyMatch(message -> "user".equals(message.role()) && "原任务".equals(message.content())));
        }
    }

    /** 同会话里工作区变了要再补一份快照，否则模型拿着过期的分支名继续干活。 */
    @Test
    void appendsAnotherSnapshotWhenTheWorkspaceChangesMidSession() throws Exception {
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<List<Message>>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            return done();
        };
        Files.writeString(workspace.resolve("before.txt"), "evidence");
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            Files.writeString(workspace.resolve("added-later.txt"), "evidence");
            String secondRun = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.service.start(fixture.sessionId, secondRun, "第二个任务");
            fixture.awaitFinished("COMPLETED");

            assertEquals(1, snapshotCount(requests.get(0)), "首条请求注入一份快照");
            List<Message> second = requests.get(1);
            assertEquals(2, snapshotCount(second), "工作区变了要补一份新快照");
            List<String> snapshots = second.stream().filter(message -> "user".equals(message.role()))
                .map(Message::content).filter(content -> content.startsWith("## 仓库现状")).toList();
            assertTrue(snapshots.getLast().contains("构建："), "新快照仍要带构建方式：" + snapshots.getLast());
            assertTrue(
                second.stream().anyMatch(message -> "user".equals(message.role()) && "第二个任务".equals(message.content())),
                "本步输入仍在快照之后");
        }
    }

    @Test
    void reusesSnapshotWhileWorkspaceIsUnchangedAndRegeneratesAfterEdits() throws Exception {
        Files.writeString(workspace.resolve("preexisting.txt"), "evidence");
        List<String> snapshots = new CopyOnWriteArrayList<>();
        ModelClient model = (messages, tools) -> {
            snapshots.add(snapshotOf(messages));
            return done();
        };
        // 目录树只在 full 档出现：非 git 工作区里 brief 档正文不含随工作区变化的内容，看不出重新生成。
        try (Fixture fixture = new Fixture(model, 8, false, 1, PermissionMode.READ_ONLY, null, "full")) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");
            // 同一工作区第二个会话：状态没变，正文直接复用缓存
            String second = fixture.sessions.createSession("另一件任务", workspace.toString()).id();
            String secondRun = fixture.service.claimRun(second).orElseThrow();
            fixture.sessions.publish(second, secondRun, new RunStarted("另一件任务"));
            fixture.service.start(second, secondRun, "另一件任务");
            fixture.awaitFinished("COMPLETED");

            try (var briefs = Files.list(dataRoot.resolve("repository-briefs"))) {
                List<String> files = briefs.map(path -> path.getFileName().toString()).toList();
                assertEquals(1, files.size(), "快照按工作区只存一份，实际：" + files);
            }
            assertEquals(snapshots.get(0), snapshots.get(1), "工作区没变时复用同一份快照正文");

            Files.writeString(workspace.resolve("brand-new-file.txt"), "evidence");
            String third = fixture.sessions.createSession("第三件任务", workspace.toString()).id();
            String thirdRun = fixture.service.claimRun(third).orElseThrow();
            fixture.sessions.publish(third, thirdRun, new RunStarted("第三件任务"));
            fixture.service.start(third, thirdRun, "第三件任务");
            fixture.awaitFinished("COMPLETED");

            assertNotEquals(snapshots.get(1), snapshots.get(2), "工作区变了要重新生成");
            assertTrue(snapshots.get(2).contains("brand-new-file.txt"), "新文件要出现在新快照里");
        }
    }

    /**
     * 延迟工具只在尾部追加：第一轮打开 browser 以后核心工具的位置与内容不能变；
     * 同会话第二次 run 的首条请求就要带上已启用的集合，不再退回精简工具表。
     */
    @Test
    void continuationInheritsDeferredToolsEnabledByThePreviousRun() throws Exception {
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<List<ToolDefinition>> toolSets = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            toolSets.add(tools);
            return calls.getAndIncrement() == 0 ? new ModelResponse("先打开浏览器",
                List.of(new ToolCall("open", "enable_tools", Map.of("names", List.of("browser"))))) : done();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.sessionExtras = List.of(deferredTool("browser"));
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            List<String> before = toolNames(toolSets.getFirst());
            assertFalse(before.contains("browser"), "打开前不声明延迟工具：" + before);
            List<String> opened = toolNames(toolSets.get(1));
            assertEquals("browser", opened.getLast(), "已启用的延迟工具应追加在核心工具之后：" + opened);
            assertEquals(before, opened.subList(0, before.size()), "打开延迟工具不能挪动已有工具");

            String secondRun = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.service.start(fixture.sessionId, secondRun, "接着处理下一件事");
            fixture.awaitFinished("COMPLETED");

            int firstRequest = requestWith(requests, "接着处理下一件事");
            assertTrue(firstRequest >= 0, requests.toString());
            List<String> inherited = toolNames(toolSets.get(firstRequest));
            assertEquals("browser", inherited.getLast(), "续跑首条请求即应带上已启用的延迟工具：" + inherited);
            assertEquals(opened, inherited, "已启用集合不变时两次请求的工具表应逐项一致");
        }
    }

    /**
     * 服务重启后内存记录已没了，续跑要能从落盘历史里认出上一轮打开过的延迟工具，
     * 而不是让模型重新 enable_tools 一遍。
     */
    @Test
    void restoresDeferredToolsFromPersistedHistoryWhenTheProcessRestarted() throws Exception {
        CopyOnWriteArrayList<List<ToolDefinition>> toolSets = new CopyOnWriteArrayList<>();
        ModelClient model = (messages, tools) -> {
            toolSets.add(tools);
            return done();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.sessionExtras = List.of(deferredTool("browser"));
            fixture.conversations.save(fixture.sessionId,
                List.of(Message.user("先看看页面"),
                    Message.assistant("",
                        List.of(new ToolCall("open", "enable_tools", Map.of("names", List.of("browser"))))),
                    Message.tool("open", "已打开：browser")));

            fixture.start();
            fixture.awaitFinished("COMPLETED");

            List<String> names = toolNames(toolSets.getFirst());
            assertEquals("browser", names.getLast(), "历史里打开过的延迟工具应在首条请求就位于尾部扩展段：" + names);
        }
    }

    /** `/review` 只读模式不继承写/扩展工具，也不能把会话已启用的集合洗掉。 */
    @Test
    void reviewRunsDoNotInheritDeferredToolsAndKeepTheSessionSet() throws Exception {
        CopyOnWriteArrayList<List<Message>> requests = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<List<ToolDefinition>> toolSets = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            toolSets.add(tools);
            return calls.getAndIncrement() == 0 ? new ModelResponse("先打开浏览器",
                List.of(new ToolCall("open", "enable_tools", Map.of("names", List.of("browser"))))) : done();
        };
        try (Fixture fixture = new Fixture(model, 8)) {
            fixture.sessionExtras = List.of(deferredTool("browser"));
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            String reviewRun = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.sessions.publish(fixture.sessionId, reviewRun, new RunStarted("/review 重点看登录模块"));
            fixture.service.start(fixture.sessionId, reviewRun, "/review 重点看登录模块");
            fixture.awaitFinished("COMPLETED");
            int reviewRequest = requestWith(requests, "/review 重点看登录模块");
            assertTrue(reviewRequest >= 0, requests.toString());
            List<String> review = toolNames(toolSets.get(reviewRequest));
            assertFalse(review.contains("browser"), "审查 run 只读，不继承扩展工具：" + review);

            String thirdRun = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.service.start(fixture.sessionId, thirdRun, "回到普通任务");
            fixture.awaitFinished("COMPLETED");
            int thirdRequest = requestWith(requests, "回到普通任务");
            assertTrue(thirdRequest >= 0, requests.toString());
            assertTrue(toolNames(toolSets.get(thirdRequest)).contains("browser"),
                "审查 run 不能清掉会话已启用的延迟工具：" + toolNames(toolSets.get(thirdRequest)));
        }
    }

    /** 第一条带指定用户输入的请求下标：用于定位某次 run 的首条请求。 */
    private static int requestWith(List<List<Message>> requests, String text) {
        for (int index = 0; index < requests.size(); index++) {
            if (requests.get(index).stream()
                .anyMatch(message -> "user".equals(message.role()) && text.equals(message.content()))) {
                return index;
            }
        }
        return -1;
    }

    /** 请求里工具定义的名称顺序。 */
    private static List<String> toolNames(List<ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::name).toList();
    }

    /** 一个可用的延迟工具，用来验证 enable_tools 之后的排序与会话继承。 */
    private static Tool deferredTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test deferred tool";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return true;
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "ok";
            }
        };
    }

    /** 请求里的 system 提示词（第一条 system 消息）。 */
    private static String systemOf(List<Message> messages) {
        return messages.stream().filter(message -> "system".equals(message.role())).map(Message::content).findFirst()
            .orElse("");
    }

    /** 往工作区写一个项目级技能。 */
    private void writeSkill(String name, String description, String body) throws Exception {
        var skill = Files.createDirectories(workspace.resolve(".pilot/skills").resolve(name));
        Files.writeString(skill.resolve("SKILL.md"), "---\ndescription: " + description + "\n---\n" + body);
    }

    @Test
    void installsAndLoadsSkillInOneAgentRunFromAnInitiallyEmptyCatalog() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("install_skill")));
            assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("use_skill")));
            int step = calls.getAndIncrement();
            if (step == 0) {
                return new ModelResponse(null,
                    List.of(new ToolCall("install", "install_skill",
                        Map.of("action", "install", "source", "https://github.com/team/repo/tree/main/skills/demo"))),
                    null);
            }
            if (step == 1) {
                assertTrue(messages.stream().anyMatch(
                    message -> message.content() != null && message.content().contains("\"available\":true")));
                return new ModelResponse(null, List.of(new ToolCall("load", "use_skill", Map.of("name", "demo"))),
                    null);
            }
            assertTrue(messages.stream().anyMatch(
                message -> message.content() != null && message.content().contains("Installed instruction marker")));
            return done();
        };
        try (var fixture = new Fixture(model, 8, false, 1, PermissionMode.AUTO)) {
            var dataDir = new PilotDataDir(dataRoot.toString());
            var properties =
                new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 1, 0, 60000, false);
            var skillService = new SkillService(new SkillProperties(true, null), properties, dataDir);
            fixture.preparation.setSkillInstaller(new SkillInstallService(skillService, dataDir, (url, target) -> {
                Path directory = Files.createDirectories(target.resolve("skills/demo"));
                Files.writeString(directory.resolve("SKILL.md"),
                    "---\nname: demo\ndescription: Demo skill\n---\nInstalled instruction marker");
            }, 600000, 2000, 67108864, 500, 20971520));
            fixture.start();
            fixture.awaitFinished("COMPLETED");
            assertEquals(3, calls.get());
            assertTrue(Files.exists(workspace.resolve(".pilot/skills/demo/SKILL.md")));
        }
    }

    @Test
    void emptyWorkspaceHasNoSkillSurface() throws Exception {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var toolSets = new CopyOnWriteArrayList<List<ToolDefinition>>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            toolSets.add(tools);
            return done();
        };
        try (var fixture = new Fixture(model, 8)) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            assertTrue(toolSets.get(0).stream().noneMatch(tool -> "use_skill".equals(tool.name())),
                "无技能时不注册 use_skill");
            assertFalse(systemOf(requests.get(0)).contains("## 技能"), "无技能时目录段整体消失，零 token 成本");
        }
    }

    @Test
    void skillRegistersUseSkillAndCatalogSection() throws Exception {
        writeSkill("demo", "审阅当前未提交的改动。", "## 流程\n1. 查状态");
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var toolSets = new CopyOnWriteArrayList<List<ToolDefinition>>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            toolSets.add(tools);
            return done();
        };
        try (var fixture = new Fixture(model, 8)) {
            fixture.start();
            fixture.awaitFinished("COMPLETED");

            assertTrue(toolSets.get(0).stream().anyMatch(tool -> "use_skill".equals(tool.name())));
            var useSkill =
                toolSets.get(0).stream().filter(tool -> "use_skill".equals(tool.name())).findFirst().orElseThrow();
            assertFalse(useSkill.description().isBlank(), "描述来自 prompts/tools/use_skill.md");
            String system = systemOf(requests.get(0));
            assertTrue(system.contains("## 技能"), "目录段随 use_skill 注册而注入");
            assertTrue(system.contains("- demo：审阅当前未提交的改动。"));
        }
    }

    @Test
    void explicitSkillInjectsGuidanceBeforeUserInputAndPersists() throws Exception {
        writeSkill("demo", "审阅当前未提交的改动。", "## 流程\n1. 查状态");
        var requests = new CopyOnWriteArrayList<List<Message>>();
        ModelClient model = (messages, tools) -> {
            requests.add(messages);
            return done();
        };
        try (var fixture = new Fixture(model, 8)) {
            fixture.startWithSkill("正文任务", "demo");
            fixture.awaitFinished("COMPLETED");

            List<Message> first = requests.get(0);
            int activation = -1;
            int input = -1;
            for (int i = 0; i < first.size(); i++) {
                String text = first.get(i).content();
                if (!"user".equals(first.get(i).role())) {
                    continue;
                }
                if (text.startsWith("（用户为本任务指定了技能 demo")) {
                    activation = i;
                }
                if ("正文任务".equals(text)) {
                    input = i;
                }
            }
            assertTrue(activation >= 0, "激活指引应作为独立 user 消息注入");
            assertTrue(activation < input, "激活消息应位于用户输入之前");
            assertTrue(fixture.conversations.load(fixture.sessionId).stream()
                .anyMatch(message -> message.content().startsWith("（用户为本任务指定了技能 demo")), "激活消息随对话记忆落盘，续跑/压缩不丢");
        }
    }

    @Test
    void keepsQueueWhenStepLimitPausesTask() throws Exception {
        assertKeepsQueue(1, List.of(), AgentRunServiceTest::readFile, "MAX_STEPS");
    }

    /** 关闭循环完成检查时，服务器仍保留未完成计划并等待复核，不自动出队。 */
    @Test
    void keepsQueueWhenModelStopsWithAnOpenPlan() throws Exception {
        assertKeepsQueue(8, List.of(new PlanItem("还没做完的原任务", PlanItem.PENDING)), AgentRunServiceTest::done,
            "AWAITING_REVIEW");
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
            assertEquals(SessionStatus.RUNNING, fixture.sessions.find(fixture.sessionId).orElseThrow().status());
            String continuedRun = fixture.service.activeRun(fixture.sessionId).orElseThrow();
            assertNotEquals(fixture.runId, continuedRun);
            List<EventEnvelope> continuationEvents = fixture.sessions.events(fixture.sessionId).stream()
                .filter(event -> event.runId().equals(continuedRun)).toList();
            assertTrue(continuationEvents.get(0).payload() instanceof RunStarted);
            assertTrue(continuationEvents.get(1).payload() instanceof RunContinued);
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
            if (call < 4) {
                return new ModelResponse("",
                    List.of(new ToolCall("read" + call, "read_file", Map.of("path", "A" + call + ".java"))));
            }
            return done();
        };
        try (Fixture fixture = new Fixture(model, 4, true)) {
            fixture.start();
            fixture.awaitFinished("MAX_STEPS");
            assertEquals(4, executionCalls.get());
            TaskControlService tasks =
                new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(dataRoot.toString()));
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
            if (calls.getAndIncrement() == 0) {
                return readFile();
            }
            current[0].plans.save(current[0].sessionId, List.of(new PlanItem("原任务计划", PlanItem.COMPLETED)));
            return done();
        };
        try (Fixture fixture = new Fixture(model, 1, true)) {
            current[0] = fixture;
            fixture.plans.save(fixture.sessionId, List.of(new PlanItem("原任务计划", PlanItem.PENDING)));
            fixture.start();
            fixture.awaitFinished("MAX_STEPS", "COMPLETED");
            assertEquals(2, calls.get());
            TaskControlService tasks =
                new TaskControlService(JsonMapper.builder().build(), new PilotDataDir(dataRoot.toString()));
            assertEquals(1, tasks.load(fixture.sessionId).execution().budgetSegments());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directBudgetContinuesOnlyAfterMakingProgress(boolean completesStep) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture[] current = new Fixture[1];
        ModelClient model = (messages, tools) -> {
            if (tools.isEmpty() && messages.getLast().content().startsWith("[系统收尾要求]")) {
                return new ModelResponse("进度已保存", List.of());
            }
            if (calls.getAndIncrement() == 0 && completesStep) {
                current[0].plans.save(current[0].sessionId,
                    List.of(new PlanItem("第一步", PlanItem.COMPLETED), new PlanItem("仍未完成", PlanItem.PENDING)));
            }
            return readFile();
        };
        try (Fixture fixture = new Fixture(model, 12, true, 1, PermissionMode.READ_ONLY, 4)) {
            current[0] = fixture;
            fixture.plans.save(fixture.sessionId,
                List.of(new PlanItem("第一步", PlanItem.PENDING), new PlanItem("仍未完成", PlanItem.PENDING)));
            fixture.start();
            fixture.awaitFinished("BUDGET_LIMITED");
            if (completesStep) {
                fixture.awaitFinished("BUDGET_LIMITED");
            }

            assertEquals(completesStep ? 8 : 4, calls.get());
            assertEquals(completesStep ? 1 : 0, fixture.sessions.events(fixture.sessionId).stream().filter(
                event -> event.payload() instanceof RunContinued continued && continued.reason().startsWith("预算分段继续"))
                .count());
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

    private void assertKeepsQueue(int maxSteps, List<PlanItem> plan, Supplier<ModelResponse> response, String status)
        throws Exception {
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
            assertEquals("MAX_STEPS".equals(status) ? 2 : 1, calls.get(), "步数耗尽可生成一次阶段说明；模型端点失败后不得追加收尾请求");
            assertTrue(fixture.service.activeRun(fixture.sessionId).isEmpty());
        } finally {
            release.countDown();
        }
    }

    private class Fixture implements AutoCloseable {
        final SessionService sessions = new SessionService();
        final InMemoryPlanRepository plans = new InMemoryPlanRepository();
        final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        final BlockingQueue<String> finished = new LinkedBlockingQueue<>();
        final String sessionId = sessions.createSession("test", workspace.toString()).id();
        final RunMetrics metrics;
        final AgentRunService service;
        final AgentRunPreparation preparation;
        final com.pilot.core.tool.command.CommandTaskManager commandTasks;
        /** 会话级扩展工具：默认空，测试在 start 前覆盖成 browser 之类延迟工具。 */
        List<com.pilot.core.tool.Tool> sessionExtras = List.of();
        String runId;

        Fixture(ModelClient model, int maxSteps) {
            this(model, maxSteps, false);
        }

        Fixture(ModelClient model, int maxSteps, boolean goals) {
            this(model, maxSteps, goals, 1);
        }

        Fixture(ModelClient model, int maxSteps, boolean goals, int runThreads) {
            this(model, maxSteps, goals, runThreads, PermissionMode.READ_ONLY);
        }

        Fixture(ModelClient model, int maxSteps, boolean goals, int runThreads, PermissionMode mode) {
            this(model, maxSteps, goals, runThreads, mode, null);
        }

        Fixture(ModelClient model, int maxSteps, boolean goals, int runThreads, PermissionMode mode,
            Integer directMaxRequests) {
            this(model, maxSteps, goals, runThreads, mode, directMaxRequests, null);
        }

        Fixture(ModelClient model, int maxSteps, boolean goals, int runThreads, PermissionMode mode,
            Integer directMaxRequests, String repositoryBrief) {
            AgentProperties properties =
                new AgentProperties(workspace.toString(), mode, maxSteps, 3, 5, 300, runThreads, 0, 60_000, false,
                    goals, directMaxRequests, null, null, null, null, null, null, null, null, null, repositoryBrief);
            JsonMapper mapper = JsonMapper.builder().build();
            PilotDataDir dataDir = new PilotDataDir(dataRoot.toString());
            commandTasks = new com.pilot.core.tool.command.CommandTaskManager(dataDir.file("commands"), mapper);
            WorkspaceToolFactory factory = mock(WorkspaceToolFactory.class, withSettings().mockMaker(SUBCLASS));
            when(factory.resolve(anyString())).thenReturn(workspace);
            when(factory.registryForScope(anyString(), any(com.pilot.core.workspace.AgentScope.class),
                any(com.pilot.core.tool.SandboxSink.class), anyList())).thenAnswer(invocation -> {
                    // /review 独立模式的只读工具表（真实实现里由 AgentScope 裁剪）：测试里至少提供读工具
                    ToolRegistry tools = new ToolRegistry();
                    tools.register(new ReadFileTool(new PathValidator(workspace)));
                    return tools;
                });
            when(factory.registryFor(anyString(), anyList(), any(PlanSink.class))).thenAnswer(invocation -> {
                ToolRegistry tools = new ToolRegistry();
                tools.register(new ReadFileTool(new PathValidator(workspace)));
                if (goals) {
                    tools.register(new com.pilot.core.tool.Tool() {
                        @Override
                        public String name() {
                            return "edit_file";
                        }

                        @Override
                        public String description() {
                            return "test edit";
                        }

                        @Override
                        public Map<String, Object> parameters() {
                            return Map.of("type", "object");
                        }

                        @Override
                        public boolean readOnly() {
                            return true;
                        }

                        @Override
                        public String execute(Map<String, Object> args) {
                            return "updated";
                        }

                        @Override
                        public com.pilot.core.tool.ToolResult executeResult(Map<String, Object> args) {
                            return new com.pilot.core.tool.ToolResult("updated",
                                List.of(String.valueOf(args.get("path"))), List.of());
                        }
                    });
                }
                return tools;
            });
            // 延迟工具（browser / mcp）由 sessionExtras 注册；测试开始前可覆盖，模拟已安装的扩展
            when(factory.sessionExtras(anyString(), anyString())).thenAnswer(invocation -> sessionExtras);
            metrics = new RunMetrics() {
                @Override
                public void recordRun(String status, long durationMs) {
                    super.recordRun(status, durationMs);
                    finished.add(status);
                }
            };
            RunSnapshotService snapshots = new RunSnapshotService(mapper, dataDir);
            TaskControlService taskControls = new TaskControlService(mapper, dataDir);
            SkillService skills = new SkillService(new SkillProperties(true, null), properties, dataDir);
            MultiAgentProperties multiProperties = new MultiAgentProperties(false, 1, 1, 8, 30, List.of());
            preparation = new AgentRunPreparation(sessions, factory, properties, multiProperties, taskControls,
                commandTasks, snapshots, plans, mapper, skills);
            service = new AgentRunService(sessions, model, factory, new PolicyPermissionGate(mode),
                ApprovalBroker.denyAll(), properties,
                new ModelSettings(new ModelProperties("", "http://localhost", "test", List.of()),
                    new InMemorySettingsStore()),
                snapshots, new RunRegistry(), RunRepository.noop(), conversations, plans, mapper, dataDir,
                new QueuedMessageRepository(), null, multiProperties, metrics, taskControls, commandTasks, preparation,
                new DeferredToolRegistry(), null, null, null);
        }

        void start() {
            runId = service.claimRun(sessionId).orElseThrow();
            sessions.publish(sessionId, runId, new RunStarted("原任务"));
            service.start(sessionId, runId, "原任务");
        }

        void startWithSkill(String input, String skill) {
            runId = service.claimRun(sessionId).orElseThrow();
            sessions.publish(sessionId, runId, new RunStarted(input));
            service.start(sessionId, runId, input, List.of(), null, skill);
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
        return messages.stream().filter(message -> "user".equals(message.role())).map(Message::content)
            .filter(content -> !content.startsWith("## 仓库现状"))
            .filter(content -> !content.contains("[Runtime task context]")).toList();
    }

    private static ModelResponse readFile() {
        return new ModelResponse("原任务还在进行", List.of(new ToolCall("call_1", "read_file", Map.of("path", "a.txt"))));
    }

    private static ModelResponse done() {
        return new ModelResponse("完成", List.of());
    }

    @Test
    void preflightLocalizationRunsOnSecondTurnWithoutPollutingHistoryAndRefreshesOnPlanAdvance() throws Exception {
        Files.createDirectories(workspace.resolve("src/alpha"));
        Files.createDirectories(workspace.resolve("src/beta"));
        Files.writeString(workspace.resolve("src/alpha/AlphaCheckoutService.java"),
            "class AlphaCheckoutService { void settle() {} }\n");
        Files.writeString(workspace.resolve("src/beta/BetaLedgerEngine.java"),
            "class BetaLedgerEngine { void reconcile() {} }\n");

        List<List<Message>> seenRequests = new CopyOnWriteArrayList<>();
        ModelClient model = preflightPlanAdvanceModel(seenRequests);
        try (Fixture fixture = new Fixture(model, 10, false, 1, PermissionMode.AUTO)) {
            BlockingQueue<String> doneStatuses = new LinkedBlockingQueue<>();
            AgentRunService runService = preflightRunService(fixture, model, doneStatuses);
            try {
                // 第 1 条消息（泛化寒暄）
                runAndWait(runService, fixture, doneStatuses, "你好");
                assertFalse(seenRequests.get(0).stream().anyMatch(m -> m.content().contains("<candidate-targets")));

                // 第 2 条消息（同会话非首条消息！仍应获得 AlphaCheckoutService 预定位）
                String task2 = "修复 AlphaCheckoutService 的结算逻辑";
                runAndWait(runService, fixture, doneStatuses, task2);

                assertTrue(
                    seenRequests.get(1).stream()
                        .anyMatch(m -> "system".equals(m.role()) && m.content().contains("AlphaCheckoutService.java")),
                    "同会话第 2 条任务首步应获得预定位");
                assertTrue(
                    seenRequests.get(2).stream()
                        .anyMatch(m -> "system".equals(m.role()) && m.content().contains("AlphaCheckoutService.java")),
                    "首轮调 update_plan 后第 2 步仍应能看到初始候选");
                assertTrue(
                    seenRequests.get(3).stream()
                        .anyMatch(m -> "system".equals(m.role()) && m.content().contains("BetaLedgerEngine.java")),
                    "计划推进到高置信新步骤应刷新阶段锚点");
                List<Message> saved = fixture.conversations.load(fixture.sessionId);
                assertFalse(saved.stream().anyMatch(m -> m.content().contains("<candidate-targets")));
                assertEquals(List.of("你好", task2), userInputs(saved));
            } finally {
                runService.shutdown();
            }
        }
    }

    private static ModelClient preflightPlanAdvanceModel(List<List<Message>> seenRequests) {
        AtomicInteger callNo = new AtomicInteger();
        return new ModelClient() {
            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                seenRequests.add(List.copyOf(messages));
                return switch (callNo.incrementAndGet()) {
                    case 1 -> done();
                    case 2 -> planCall("p1", "in_progress", "pending", null);
                    case 3 -> planCall("p2", "completed", "in_progress", null);
                    case 4 -> planCall("p3", "completed", "completed", "in_progress");
                    case 5 -> planCall("p4", "completed", "completed", "completed");
                    default -> done();
                };
            }
        };
    }

    private static ModelResponse planCall(String id, String s1, String s2, String s3) {
        List<Map<String, String>> items = s3 == null
            ? List.of(Map.of("step", "修改 AlphaCheckoutService", "status", s1),
                Map.of("step", "核对 BetaLedgerEngine 对账逻辑", "status", s2))
            : List.of(Map.of("step", "修改 AlphaCheckoutService", "status", s1),
                Map.of("step", "核对 BetaLedgerEngine 对账逻辑", "status", s2), Map.of("step", "运行回归测试并收尾", "status", s3));
        return new ModelResponse("", List.of(new ToolCall(id, "update_plan", Map.of("plan", items))));
    }

    private AgentRunService preflightRunService(Fixture fixture, ModelClient model,
        BlockingQueue<String> doneStatuses) {
        PathValidator validator = new PathValidator(workspace);
        WorkspaceToolFactory realPlanFactory = mock(WorkspaceToolFactory.class, withSettings().mockMaker(SUBCLASS));
        when(realPlanFactory.resolve(anyString())).thenReturn(workspace);
        when(realPlanFactory.registryFor(anyString(), anyList(), any(PlanSink.class))).thenAnswer(inv -> {
            PlanSink sink = inv.getArgument(2);
            ToolRegistry reg = new ToolRegistry();
            reg.register(new ReadFileTool(validator, new ReadTracker()));
            reg.register(new CodeSymbolTool(validator));
            reg.register(new UpdatePlanTool(sink, () -> fixture.plans.load(fixture.sessionId)));
            return reg;
        });
        AgentProperties props = new AgentProperties(workspace.toString(), PermissionMode.AUTO, 10, 3, 5, 300, 1, 0,
            60_000, false, false, null, null, null, null, null, null, null, null, null, null, true, null);
        JsonMapper mapper = JsonMapper.builder().build();
        PilotDataDir dir = new PilotDataDir(dataRoot.resolve("preflight").toString());
        RunMetrics metrics = new RunMetrics() {
            @Override
            public void recordRun(String status, long durationMs) {
                super.recordRun(status, durationMs);
                doneStatuses.add(status);
            }
        };
        RunSnapshotService snapshots = new RunSnapshotService(mapper, dir);
        TaskControlService taskControls = new TaskControlService(mapper, dir);
        MultiAgentProperties multiProperties = new MultiAgentProperties(false, 1, 1, 8, 30, List.of());
        AgentRunPreparation preparation = new AgentRunPreparation(fixture.sessions, realPlanFactory, props,
            multiProperties, taskControls, fixture.commandTasks, snapshots, fixture.plans, mapper,
            new SkillService(new SkillProperties(true, null), props, dir));
        return new AgentRunService(fixture.sessions, model, realPlanFactory,
            new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(), props,
            new ModelSettings(new ModelProperties("", "http://localhost", "test", List.of()),
                new InMemorySettingsStore()),
            snapshots, new RunRegistry(), RunRepository.noop(), fixture.conversations, fixture.plans, mapper, dir,
            new QueuedMessageRepository(), null, multiProperties, metrics, taskControls, fixture.commandTasks,
            preparation, new DeferredToolRegistry(), null, null, null);
    }

    private static void runAndWait(AgentRunService runService, Fixture fixture, BlockingQueue<String> doneStatuses,
        String input) throws Exception {
        String runId = runService.claimRun(fixture.sessionId).orElseThrow();
        fixture.sessions.publish(fixture.sessionId, runId, new RunStarted(input));
        runService.start(fixture.sessionId, runId, input);
        assertEquals("COMPLETED", doneStatuses.poll(5, TimeUnit.SECONDS));
    }

    @Test
    @ExtendWith(WindowsJdkLoopbackCondition.class)
    void preflightLocalizationRespectsDisableFlagAndWorkspaceToolsExposeParallelCodeSymbol() throws Exception {
        Files.writeString(workspace.resolve("PaymentGateway.java"), """
            public class PaymentGateway {
                public boolean charge(long amount) {
                    return amount > 0;
                }
            }
            """);
        List<List<Message>> seenRequests = new CopyOnWriteArrayList<>();
        ModelClient model = (messages, tools) -> {
            seenRequests.add(List.copyOf(messages));
            return done();
        };

        try (Fixture fixture = new Fixture(model, 5, false, 1, PermissionMode.AUTO)) {
            AgentProperties disabled = new AgentProperties(workspace.toString(), PermissionMode.AUTO, 5, 3, 5, 300, 1,
                0, 60_000, false, false, null, null, null, null, null, null, null, null, null, null, false, null);
            JsonMapper mapper = JsonMapper.builder().build();
            PilotDataDir dir = new PilotDataDir(dataRoot.resolve("preflight-off").toString());
            BlockingQueue<String> doneStatuses = new LinkedBlockingQueue<>();
            RunMetrics metrics = new RunMetrics() {
                @Override
                public void recordRun(String status, long durationMs) {
                    super.recordRun(status, durationMs);
                    doneStatuses.add(status);
                }
            };
            WorkspaceToolFactory realFactory = new WorkspaceToolFactory(disabled,
                new WebSearchProperties(false, "", "", ""), SnapshotSink.none(), mapper);
            ToolRegistry built = realFactory.registryFor(workspace.toString());
            assertTrue(built.names().contains(CodeSymbolTool.NAME),
                "无外部编译器环境下 WorkspaceToolFactory 也应默认暴露 code_symbol");
            assertTrue(built.find(CodeSymbolTool.NAME).orElseThrow().parallelSafe());

            RunSnapshotService snapshots = new RunSnapshotService(mapper, dir);
            TaskControlService taskControls = new TaskControlService(mapper, dir);
            MultiAgentProperties multiProperties = new MultiAgentProperties(false, 1, 1, 8, 30, List.of());
            AgentRunPreparation preparation = new AgentRunPreparation(fixture.sessions, realFactory, disabled,
                multiProperties, taskControls, fixture.commandTasks, snapshots, fixture.plans, mapper,
                new SkillService(new SkillProperties(true, null), disabled, dir));
            AgentRunService runService = new AgentRunService(fixture.sessions, model, realFactory,
                new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(), disabled,
                new ModelSettings(new ModelProperties("", "http://localhost", "test", List.of()),
                    new InMemorySettingsStore()),
                snapshots, new RunRegistry(), RunRepository.noop(), fixture.conversations, fixture.plans, mapper, dir,
                new QueuedMessageRepository(), null, multiProperties, metrics, taskControls, fixture.commandTasks,
                preparation, new DeferredToolRegistry(), null, null, null);
            try {
                String runId = runService.claimRun(fixture.sessionId).orElseThrow();
                String prompt = "排查 PaymentGateway 的 charge 逻辑";
                fixture.sessions.publish(fixture.sessionId, runId, new RunStarted(prompt));
                runService.start(fixture.sessionId, runId, prompt);
                assertEquals("COMPLETED", doneStatuses.poll(5, TimeUnit.SECONDS));
                assertFalse(seenRequests.getFirst().stream().anyMatch(m -> m.content().contains("<candidate-targets")),
                    "关闭 preflightLocalizationEnabled 后不应注入候选目标块");
            } finally {
                runService.shutdown();
            }
        }
    }

    @Test
    void autoReconnectsAndCompletesAfterTransientConnectionDropButNeverOnPermanentError() throws Exception {
        AtomicInteger flakyCalls = new AtomicInteger();
        ModelClient flaky = (messages, tools) -> {
            if (flakyCalls.incrementAndGet() <= 3) {
                throw new ModelClientException(ModelFailureKind.NETWORK, "流式模型请求失败（10 ms）：connection reset",
                    new IOException("connection reset"));
            }
            return new ModelResponse("断网重连后已完成任务。", List.of(), null, ModelResponse.FinishReason.STOP);
        };
        try (Fixture fixture = new Fixture(flaky, 8)) {
            BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
            List<SessionEvent> observedEvents = new CopyOnWriteArrayList<>();
            fixture.sessions.subscribe(fixture.sessionId, 0, envelope -> {
                observedEvents.add(envelope.payload());
                if (envelope.payload() instanceof RunCompleted) {
                    statuses.offer("COMPLETED");
                } else if (envelope.payload() instanceof RunFailed failed) {
                    statuses.offer("FAILED:" + failed.code() + ":" + failed.retryable());
                }
            });
            fixture.service.networkReconnectDelayMs = 0L;
            String runId = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.sessions.publish(fixture.sessionId, runId, new RunStarted("分析目录结构"));
            fixture.service.start(fixture.sessionId, runId, "分析目录结构");
            assertEquals("FAILED:MODEL_ERROR:true", statuses.poll(15, TimeUnit.SECONDS));
            assertEquals("COMPLETED", statuses.poll(15, TimeUnit.SECONDS), "可重试连接中断后应自动重连续跑直至完成");
            assertTrue(observedEvents.stream().filter(RunContinued.class::isInstance).map(RunContinued.class::cast)
                .anyMatch(c -> c.reason().contains("自动重连续跑")));
        }

        AtomicInteger permCalls = new AtomicInteger();
        ModelClient permanent = (messages, tools) -> {
            permCalls.incrementAndGet();
            throw ModelClientException.httpError(ModelFailureKind.PERMANENT, 401, -1L, "HTTP 401 unauthorized");
        };
        try (Fixture fixture = new Fixture(permanent, 8)) {
            BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
            fixture.sessions.subscribe(fixture.sessionId, 0, envelope -> {
                if (envelope.payload() instanceof RunFailed failed) {
                    statuses.offer("FAILED:" + failed.code() + ":" + failed.retryable());
                }
            });
            fixture.service.networkReconnectDelayMs = 0L;
            String runId = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.sessions.publish(fixture.sessionId, runId, new RunStarted("分析目录结构"));
            fixture.service.start(fixture.sessionId, runId, "分析目录结构");
            assertTrue(statuses.poll(10, TimeUnit.SECONDS).startsWith("FAILED:MODEL_ERROR:"));
            assertEquals(null, statuses.poll(300, TimeUnit.MILLISECONDS), "永久错误（401）不得触发自动重连续跑");
            assertEquals(1, permCalls.get());
        }
    }

    @Test
    void manualCompactionReusesWarmMainPrefixAndToolsWhenWorkspaceMatches() throws Exception {
        ModelProfile anthropicShort =
            ModelProfile.resolve(null, "claude-sonnet-4-6", "anthropic", "explicit", "", null, null, "short", null);
        List<List<Message>> capturedRequests = new CopyOnWriteArrayList<>();
        List<List<ToolDefinition>> capturedTools = new CopyOnWriteArrayList<>();
        List<String> capturedPurposes = new CopyOnWriteArrayList<>();

        ModelClient model = new ModelClient() {
            @Override
            public ModelProfile profile() {
                return anthropicShort;
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                capturedRequests.add(List.copyOf(messages));
                capturedTools.add(List.copyOf(tools));
                capturedPurposes.add(ModelRequestScope.current().purpose());
                if ("compaction".equals(ModelRequestScope.current().purpose())) {
                    return new ModelResponse("<analysis>核对上下文</analysis><summary>## 保留结果\n保留事务边界</summary>", List.of(),
                        new ModelUsage(500, 30, 0, 420, 0, true), ModelResponse.FinishReason.STOP);
                }
                return new ModelResponse("首轮完成。", List.of(), new ModelUsage(400, 12, 0, 300, 0, true),
                    ModelResponse.FinishReason.STOP);
            }
        };

        try (Fixture fixture = new Fixture(model, 8)) {
            String runId = fixture.service.claimRun(fixture.sessionId).orElseThrow();
            fixture.sessions.publish(fixture.sessionId, runId, new RunStarted("检查项目结构"));
            fixture.service.start(fixture.sessionId, runId, "检查项目结构");
            assertEquals("COMPLETED", fixture.finished.poll(10, TimeUnit.SECONDS));

            int mainIdx = capturedPurposes.indexOf("main");
            assertTrue(mainIdx >= 0);

            List<Message> largeHistory = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                largeHistory.add(Message.assistant("step " + i,
                    List.of(new ToolCall("call_" + i, "read_file", Map.of("path", "f" + i)))));
                largeHistory.add(Message.tool("call_" + i, "x".repeat(2_500)));
            }
            fixture.conversations.save(fixture.sessionId, largeHistory);

            var compacted = fixture.service.compactContext(fixture.sessionId, "保留事务边界");
            assertTrue(compacted.compacted());

            int compactIdx = capturedPurposes.lastIndexOf("compaction");
            assertTrue(compactIdx > mainIdx);
            List<Message> compactRequest = capturedRequests.get(compactIdx);
            assertTrue(compactRequest.size() > 2, "预热后手动压缩必须复用主会话前缀而不退化为 2 条短摘录");
            assertEquals(capturedRequests.get(mainIdx).getFirst().content(), compactRequest.getFirst().content(),
                "手动压缩 system prompt 必须与主会话字节一致");
            assertEquals(capturedTools.get(mainIdx), capturedTools.get(compactIdx), "手动压缩 tools 定义必须与主会话完全一致");
            assertTrue(compactRequest.getLast().content().contains("保留事务边界"));
        }
    }

    @Test
    void coldManualCompactionFallsBackToShortSummaryOnColdShortProfile() throws Exception {
        List<List<Message>> capturedRequests = new CopyOnWriteArrayList<>();
        List<List<ToolDefinition>> capturedTools = new CopyOnWriteArrayList<>();
        ModelClient model = (messages, tools) -> {
            capturedRequests.add(List.copyOf(messages));
            capturedTools.add(List.copyOf(tools));
            return new ModelResponse("<summary>冷启动短摘要</summary>", List.of());
        };

        try (Fixture fixture = new Fixture(model, 8)) {
            List<Message> largeHistory = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                largeHistory.add(Message.assistant("step " + i,
                    List.of(new ToolCall("call_" + i, "read_file", Map.of("path", "f" + i)))));
                largeHistory.add(Message.tool("call_" + i, "x".repeat(2_500)));
            }
            fixture.conversations.save(fixture.sessionId, largeHistory);

            var compacted = fixture.service.compactContext(fixture.sessionId, null);
            assertTrue(compacted.compacted());
            assertEquals(1, capturedRequests.size());
            assertEquals(2, capturedRequests.getFirst().size(), "冷会话或未命中缓存时安全走 2 条短摘录");
            assertTrue(capturedTools.getFirst().isEmpty());
        }
    }
}
