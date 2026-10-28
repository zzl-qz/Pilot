package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.ApprovalDecision;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunSteered;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PermissionResult;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ProducesCheck;
import com.pilot.core.tool.WritesFiles;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopControlTest {
    @Test
    void correctionDuringModelRequestSkipsObsoleteToolBatch() throws Exception {
        var called = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pending = new ConcurrentLinkedQueue<String>();
        var executions = new AtomicInteger();
        var turns = new AtomicInteger();
        var requests = new ArrayList<List<Message>>();
        var events = new ArrayList<SessionEvent>();
        ModelClient model = (messages, definitions) -> {
            requests.add(messages);
            if (turns.getAndIncrement() == 0) {
                called.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return new ModelResponse("旧方案", List.of(call("a"), call("b")));
            }
            return new ModelResponse("按纠正后的范围完成", List.of());
        };
        var loop = loop(model, registry(() -> executions.incrementAndGet()), () -> false, pending::poll);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> loop.run("原始目标", List.of(), events::add));
            assertTrue(called.await(5, TimeUnit.SECONDS));
            pending.add("只处理测试，不修改实现");
            release.countDown();
            var result = future.get(5, TimeUnit.SECONDS);
            assertNull(result.failureCode());
            assertEquals(0, executions.get());
            assertEquals(2, turns.get());
            assertEquals(List.of("a", "b"), requests.get(1).stream().filter(m -> m.role().equals("tool"))
                    .map(Message::toolCallId).toList());
            assertEquals("只处理测试，不修改实现", requests.get(1).getLast().content());
            assertFalse(events.stream().anyMatch(e -> e instanceof AssistantDelta delta && delta.text().equals("旧方案")));
            assertEquals(1, events.stream().filter(RunCompleted.class::isInstance).count());
        }
    }

    @Test
    void steeringBetweenToolsPreservesFirstResultAndSkipsRemainingCalls() {
        var pending = new ConcurrentLinkedQueue<String>();
        var executions = new AtomicInteger();
        var turns = new AtomicInteger();
        var requests = new ArrayList<List<Message>>();
        ModelClient model = (messages, definitions) -> {
            requests.add(messages);
            return turns.getAndIncrement() == 0
                    ? new ModelResponse("", List.of(call("a"), call("b"), call("c")))
                    : new ModelResponse("已重新规划", List.of());
        };
        var loop = loop(model, registry(() -> {
            executions.incrementAndGet();
            pending.add("停止旧方案，只检查日志");
        }), () -> false, pending::poll);
        var events = new ArrayList<SessionEvent>();
        var result = loop.run("原目标", List.of(), events::add);
        assertNull(result.failureCode());
        assertEquals(1, executions.get());
        var outputs = requests.get(1).stream().filter(m -> m.role().equals("tool")).toList();
        assertEquals(List.of("a", "b", "c"), outputs.stream().map(Message::toolCallId).toList());
        assertEquals("executed", outputs.get(0).content());
        assertTrue(outputs.get(1).content().startsWith("SKIPPED:"));
        assertTrue(outputs.get(2).content().startsWith("SKIPPED:"));
        assertEquals(1, events.stream().filter(RunSteered.class::isInstance).count());
    }

    @Test
    void pauseAfterApprovalBeforeToolExecutionSkipsApprovedCallAndRemainingBatch() {
        var cancelled = new AtomicBoolean();
        var executions = new AtomicInteger();
        var events = new ArrayList<SessionEvent>();
        ModelClient model = (messages, definitions) -> new ModelResponse("", List.of(call("a"), call("b")));
        var loop = new AgentLoop(model, registry(executions::incrementAndGet),
                (call, tool) -> PermissionResult.ask("需要确认"), approved(),
                config(), cancelled::get);
        var result = loop.run("完成目标", List.of(), event -> {
            events.add(event);
            if (event instanceof ApprovalResolved approval) {
                assertEquals(ApprovalDecision.APPROVED, approval.decision());
                // 审批已经完成，此时再撤销审批 Future 无效，必须由执行前检查阻止工具。
                cancelled.set(true);
            }
        });
        assertEquals("CANCELLED", result.failureCode());
        assertEquals(0, executions.get());
        var outputs = result.conversation().stream().filter(m -> m.role().equals("tool")).toList();
        assertEquals(List.of("a", "b"), outputs.stream().map(Message::toolCallId).toList());
        assertTrue(outputs.stream().allMatch(message -> message.content().startsWith("SKIPPED:")));
        assertTrue(events.stream().filter(ToolCompleted.class::isInstance).map(ToolCompleted.class::cast)
                .allMatch(event -> event.resultSummary().startsWith("SKIPPED:")));
        assertTrue(events.stream().noneMatch(RunCompleted.class::isInstance));
    }

    @Test
    void correctionAfterApprovalSkipsApprovedToolWithoutLosingCorrectionOrClaimingChecks() {
        var pending = new ConcurrentLinkedQueue<String>();
        var executions = new AtomicInteger();
        var turns = new AtomicInteger();
        var requests = new ArrayList<List<Message>>();
        var events = new ArrayList<SessionEvent>();
        ModelClient model = (messages, definitions) -> {
            requests.add(messages);
            return turns.getAndIncrement() == 0
                    ? new ModelResponse("", List.of(call("a"), call("b")))
                    : new ModelResponse("已采用纠正后的方向", List.of());
        };
        var loop = new AgentLoop(model, registry(executions::incrementAndGet),
                (call, tool) -> PermissionResult.ask("需要确认"), approved(),
                config(), () -> false, pending::poll);
        var result = loop.run("原始目标", List.of(), event -> {
            events.add(event);
            if (event instanceof ApprovalResolved) pending.add("不要修改文件，只解释方案");
        });
        assertNull(result.failureCode());
        assertEquals(0, executions.get());
        assertEquals(2, requests.size());
        var revised = requests.get(1);
        assertEquals(List.of("system", "user", "assistant", "tool", "tool", "user"),
                revised.stream().map(Message::role).toList());
        assertEquals("不要修改文件，只解释方案", revised.getLast().content());
        assertTrue(revised.stream().filter(message -> message.role().equals("tool"))
                .allMatch(message -> message.content().startsWith("SKIPPED:")));
        assertEquals(1, events.stream().filter(RunSteered.class::isInstance).count());
        var completed = events.stream().filter(RunCompleted.class::isInstance).map(RunCompleted.class::cast)
                .findFirst().orElseThrow();
        assertEquals(List.of(), completed.changedFiles());
        assertEquals(List.of(), completed.checks());
    }

    @Test
    void correctionAfterToolStartedAlsoGuardsToolsThatDoNotNeedApproval() {
        var pending = new ConcurrentLinkedQueue<String>();
        var executions = new AtomicInteger();
        var turns = new AtomicInteger();
        ModelClient model = (messages, definitions) -> turns.getAndIncrement() == 0
                ? new ModelResponse("", List.of(call("a"), call("b")))
                : new ModelResponse("按新方向完成", List.of());
        var result = loop(model, registry(executions::incrementAndGet), () -> false, pending::poll)
                .run("原始目标", List.of(), event -> {
                    if (event instanceof ToolStarted) pending.add("保留现有文件");
                });
        assertNull(result.failureCode());
        assertEquals(0, executions.get());
        assertEquals(2, turns.get());
        assertEquals(List.of("原始目标", "保留现有文件"), result.conversation().stream()
                .filter(message -> message.role().equals("user")).map(Message::content).toList());
        assertEquals(List.of("a", "b"), result.conversation().stream()
                .filter(message -> message.role().equals("tool")).map(Message::toolCallId).toList());
    }

    @Test
    void pauseAfterFirstToolProducesProtocolCompleteHistoryForResume() {
        var cancelled = new AtomicBoolean();
        var executions = new AtomicInteger();
        var tool = registry(() -> {
            executions.incrementAndGet();
            cancelled.set(true);
        });
        ModelClient model = (messages, definitions) -> new ModelResponse("", List.of(call("a"), call("b")));
        var loop = loop(model, tool, cancelled::get, () -> null);
        var paused = loop.run("完成原目标", List.of(), event -> { });
        assertEquals("CANCELLED", paused.failureCode());
        assertEquals(1, executions.get());
        assertEquals(List.of("a", "b"), paused.conversation().stream().filter(m -> m.role().equals("tool"))
                .map(Message::toolCallId).toList());
        assertTrue(paused.conversation().getLast().content().startsWith("SKIPPED:"));
        ModelClient resumedModel = (messages, definitions) -> {
            assertEquals(paused.conversation(), messages.subList(1, messages.size()));
            return new ModelResponse("继续后完成", List.of());
        };
        var resumed = loop(resumedModel, tool, () -> false, () -> null)
                .resume(paused.conversation(), event -> { }, messages -> { });
        assertNull(resumed.failureCode());
        assertEquals("继续后完成", resumed.finalText());
    }

    @Test
    void pauseAfterLastAllowedToolStepTakesPrecedenceOverStepLimit() {
        var cancelled = new AtomicBoolean();
        ModelClient model = (messages, definitions) -> new ModelResponse("", List.of(call("a")));
        var loop = new AgentLoop(model, registry(() -> cancelled.set(true)),
                new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(),
                new AgentLoop.Config(1, 3, "system", List::of, 0, 120_000, false), cancelled::get);
        assertEquals("CANCELLED", loop.run("目标", List.of(), event -> { }).failureCode());
    }

    @Test
    void pauseDuringFinalModelResponseCannotReportCompletion() {
        var cancelled = new AtomicBoolean();
        ModelClient model = (messages, definitions) -> {
            cancelled.set(true);
            return new ModelResponse("已经完成", List.of());
        };
        var events = new ArrayList<SessionEvent>();
        var result = loop(model, registry(() -> { }), cancelled::get, () -> null)
                .run("原目标", List.of(), events::add);
        assertEquals("CANCELLED", result.failureCode());
        assertTrue(events.stream().noneMatch(RunCompleted.class::isInstance));
    }

    @Test
    void completionClaimClosesLateSteeringRace() {
        var pending = new ConcurrentLinkedQueue<String>();
        var claims = new AtomicInteger();
        var calls = new AtomicInteger();
        var events = new ArrayList<SessionEvent>();
        ModelClient model = (messages, definitions) -> {
            if (calls.getAndIncrement() > 0) assertEquals("补充验收条件", messages.getLast().content());
            return new ModelResponse("本轮结果", List.of());
        };
        var loop = loop(model, registry(() -> { }), () -> false, pending::poll)
                .withCompletionClaim(() -> {
                    if (claims.getAndIncrement() == 0) {
                        pending.add("补充验收条件");
                        return false;
                    }
                    return true;
                });
        var result = loop.run("原目标", List.of(), events::add);
        assertNull(result.failureCode());
        assertEquals(2, calls.get());
        assertEquals(1, events.stream().filter(RunCompleted.class::isInstance).count());
        assertEquals(1, events.stream().filter(RunSteered.class::isInstance).count());
    }

    @Test
    void durableTaskContextIsRebuiltForEveryStepAndOnResume() {
        var context = new AtomicReference<>("原始目标：修复登录；约束：不改数据库");
        var requests = new ArrayList<List<Message>>();
        var calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            requests.add(messages);
            return calls.getAndIncrement() == 0
                    ? new ModelResponse("", List.of(call("a"))) : new ModelResponse("完成", List.of());
        };
        var tool = registry(() -> context.set("原始目标：修复登录；约束：不改数据库；验收：重连测试通过"));
        var loop = loop(model, tool, () -> false, () -> null).withTaskContext(context::get);
        var result = loop.run("继续", List.of(), event -> { });
        assertTrue(requests.get(0).getFirst().content().contains("不改数据库"));
        assertTrue(requests.get(1).getFirst().content().contains("验收：重连测试通过"));
        assertFalse(result.conversation().stream().anyMatch(m -> m.role().equals("system")));
        loop(model, tool, () -> false, () -> null).withTaskContext(context::get)
                .resume(result.conversation(), event -> { }, messages -> { });
        assertTrue(requests.getLast().getFirst().content().contains("原始目标：修复登录"));
        assertTrue(requests.getLast().getFirst().content().contains("验收：重连测试通过"));
    }

    private static ToolCall call(String id) { return new ToolCall(id, "write", Map.of("path", id)); }

    private static AgentLoop loop(ModelClient model, ToolRegistry tools, Cancellation cancelled, Supplier<String> pending) {
        return new AgentLoop(model, tools, new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(),
                config(), cancelled, pending);
    }

    private static AgentLoop.Config config() {
        return new AgentLoop.Config(8, 3, "system", List::of, 0, 120_000, false);
    }

    private static ApprovalBroker approved() {
        return (callId, toolName, argsSummary) -> CompletableFuture.completedFuture(true);
    }

    private static ToolRegistry registry(Runnable action) {
        var registry = new ToolRegistry();
        class ControlledTool implements Tool, WritesFiles, ProducesCheck {
            public String name() { return "write"; }
            public String description() { return "test"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public boolean readOnly() { return false; }
            public String execute(Map<String, Object> args) { action.run(); return "executed"; }
            public String changedPath(ToolCall call) { return String.valueOf(call.args().get("path")); }
            public String checkSummary(ToolCall call, String result) { return "check → exit 0"; }
        }
        registry.register(new ControlledTool());
        return registry;
    }
}
