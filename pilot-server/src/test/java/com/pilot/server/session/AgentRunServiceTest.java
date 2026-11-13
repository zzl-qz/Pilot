package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.MockMakers.SUBCLASS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.session.InMemoryConversationRepository;
import com.pilot.core.session.InMemoryPlanRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.config.SkillProperties;
import com.pilot.server.observability.RunMetrics;
import com.pilot.server.store.InMemorySettingsStore;
import com.pilot.server.store.QueuedMessageRepository;
import com.pilot.server.workspace.RunSnapshotService;
import com.pilot.server.workspace.WorkspaceToolFactory;

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
            assertTrue(second.stream()
                .anyMatch(message -> "user".equals(message.role()) && "第二个任务".equals(message.content())));
        }
    }

    /** 请求里「## 仓库现状」开头的 user 消息数。 */
    private static long snapshotCount(List<Message> messages) {
        return messages.stream().filter(message -> "user".equals(message.role()))
            .filter(message -> message.content().startsWith("## 仓库现状")).count();
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
            fixture.service.setSkillInstaller(new SkillInstallService(skillService, dataDir, (url, target) -> {
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
        final com.pilot.core.tool.CommandTaskManager commandTasks;
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
            AgentProperties properties = new AgentProperties(workspace.toString(), mode, maxSteps, 3, 5, 300,
                runThreads, 0, 60_000, false, goals, null, null, null, null, null, null, null, null);
            JsonMapper mapper = JsonMapper.builder().build();
            PilotDataDir dataDir = new PilotDataDir(dataRoot.toString());
            commandTasks = new com.pilot.core.tool.CommandTaskManager(dataDir.file("commands"), mapper);
            WorkspaceToolFactory factory = mock(WorkspaceToolFactory.class, withSettings().mockMaker(SUBCLASS));
            when(factory.resolve(anyString())).thenReturn(workspace);
            when(factory.registryForScope(anyString(), any(com.pilot.core.multi.AgentScope.class),
                any(com.pilot.core.tool.SandboxSink.class))).thenAnswer(invocation -> {
                    // /review 独立模式的只读工具表（真实实现里由 AgentScope 裁剪）：测试里至少提供读工具
                    ToolRegistry tools = new ToolRegistry();
                    tools.register(new ReadFileTool(new PathValidator(workspace)));
                    return tools;
                });
            when(factory.registryFor(anyString(), any(PlanSink.class))).thenAnswer(invocation -> {
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
            metrics = new RunMetrics() {
                @Override
                public void recordRun(String status, long durationMs) {
                    super.recordRun(status, durationMs);
                    finished.add(status);
                }
            };
            service = new AgentRunService(sessions, model, factory, new PolicyPermissionGate(mode),
                ApprovalBroker.denyAll(), properties,
                new ModelSettings(new ModelProperties("", "http://localhost", "test", List.of()),
                    new InMemorySettingsStore()),
                new RunSnapshotService(mapper, dataDir), new RunRegistry(), RunRepository.noop(), conversations, plans,
                mapper, dataDir, new QueuedMessageRepository(), null,
                new MultiAgentProperties(false, 1, 1, 8, 30, List.of()), metrics,
                new TaskControlService(mapper, dataDir), commandTasks,
                new SkillService(new SkillProperties(true, null), properties, dataDir));
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
            .filter(content -> !content.startsWith("## 仓库现状")).toList();
    }

    private static ModelResponse readFile() {
        return new ModelResponse("原任务还在进行", List.of(new ToolCall("call_1", "read_file", Map.of("path", "a.txt"))));
    }

    private static ModelResponse done() {
        return new ModelResponse("完成", List.of());
    }
}
