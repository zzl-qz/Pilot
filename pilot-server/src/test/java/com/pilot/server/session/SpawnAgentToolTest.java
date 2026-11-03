package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnAgentToolTest {

    @Test
    void returnsSubagentConclusionAndEmitsEvents() {
        ModelClient client = (request, tools) -> new ModelResponse("有 3 个入口类：A、B、C", List.of());
        ArrayList<SessionEvent> events = new ArrayList<SessionEvent>();
        SpawnAgentTool tool = new SpawnAgentTool(client, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "你是测试代理"), List::of, events::add, () -> false);

        String result = tool.execute(Map.of("task", "盘点入口类"));

        assertTrue(result.startsWith("有 3 个入口类：A、B、C"));
        assertTrue(result.contains("<subagent_result>"));
        assertTrue(events.get(0) instanceof SubagentStarted started && started.task().contains("盘点入口类"));
        assertTrue(events.stream().anyMatch(event -> event instanceof SubagentFinished finished && finished.ok()),
                "子代理结束事件必须带成功标记");
    }

    @Test
    void childToolCallsCarryTheSubagentIdSoUiCanGroupThem() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(named("read_file"));
        AtomicInteger turn = new AtomicInteger();
        ModelClient client = (request, definitions) -> turn.getAndIncrement() == 0
                ? new ModelResponse("", List.of(new ToolCall("call_child_1", "read_file", Map.of())))
                : new ModelResponse("读完了", List.of());
        ArrayList<SessionEvent> events = new ArrayList<>();
        SpawnAgentTool tool = new SpawnAgentTool(client, registry,
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "sys"), List::of, events::add, () -> false);

        tool.execute(Map.of("task", "读一个文件"));

        String agentId = events.stream().filter(SubagentStarted.class::isInstance)
                .map(event -> ((SubagentStarted) event).agentId()).findFirst().orElseThrow();
        List<ToolStarted> started = events.stream().filter(ToolStarted.class::isInstance)
                .map(ToolStarted.class::cast).toList();
        assertFalse(started.isEmpty(), "子代理的工具调用要透传给时间线");
        assertTrue(started.stream().allMatch(item -> agentId.equals(item.agentId())),
                "透传的工具事件必须带上子代理归属");
    }

    @Test
    void passesTheOptionalDisplayNameToTheStartEvent() {
        ModelClient client = (request, tools) -> new ModelResponse("ok", List.of());
        ArrayList<SessionEvent> events = new ArrayList<>();
        SpawnAgentTool tool = new SpawnAgentTool(client, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "sys"), List::of, events::add, () -> false);

        tool.execute(Map.of("task", "盘点入口类", "name", "盘点入口"));
        tool.execute(Map.of("task", "再看看"));

        List<SubagentStarted> started = events.stream().filter(SubagentStarted.class::isInstance)
                .map(SubagentStarted.class::cast).toList();
        assertEquals("盘点入口", started.get(0).name());
        assertEquals(null, started.get(1).name(), "没起名就不带名字，界面自己从任务里推");
    }

    @Test
    void rejectsBlankTask() {
        ModelClient client = (request, tools) -> new ModelResponse("不该被调用", List.of());
        SpawnAgentTool tool = new SpawnAgentTool(client, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "sys"), List::of, event -> { }, () -> false);

        assertTrue(tool.execute(Map.of("task", "  ")).startsWith("ERROR"));
    }

    @Test
    void inheritsHistoryOnlyWhenRequested() {
        ArrayList<List<Message>> seen = new ArrayList<List<Message>>();
        ModelClient client = (request, tools) -> new ModelResponse("ok", List.of());
        ModelClient recording = (request, tools) -> {
            seen.add(request);
            return client.chat(request, tools);
        };
        List<Message> history = List.of(Message.user("历史里的约束"), Message.assistant("收到", List.of()));
        SpawnAgentTool tool = new SpawnAgentTool(recording, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "sys"), () -> history, event -> { }, () -> false);

        tool.execute(Map.of("task", "只做子任务"));
        tool.execute(Map.of("task", "带着历史做", "fork_turns", "all"));

        assertTrue(seen.get(0).stream().noneMatch(message -> message.content().contains("历史里的约束")),
                "默认不继承父对话");
        assertTrue(seen.get(1).stream().anyMatch(message -> message.content().contains("历史里的约束")),
                "fork_turns=all 时应带上父对话");
    }

    @Test
    void freezesFilteredToolSnapshotAndCannotRecursivelySpawnOrControlGoal() {
        ToolRegistry parent = new ToolRegistry();
        parent.register(named("read_file"));
        parent.register(named("promote_goal"));
        parent.register(named("dispatch_agents"));
        ArrayList<Set<String>> visible = new ArrayList<>();
        ModelClient client = (request, definitions) -> {
            visible.add(definitions.stream().map(ToolDefinition::name)
                    .collect(java.util.stream.Collectors.toSet()));
            return new ModelResponse("isolated", List.of());
        };
        SpawnAgentTool tool = new SpawnAgentTool(client, parent,
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(20, 0, 10, "sys"), List::of, event -> { }, () -> false);

        // 构造后的父注册表变化不能渗入子代理。
        parent.register(named("spawn_agent"));
        parent.register(named("late_parent_tool"));
        assertEquals("isolated\n\n<subagent_result>\nchanged_files: []\nchecks: []\nopen_steps: []\n</subagent_result>",
                tool.execute(Map.of("task", "inspect")));

        assertEquals(Set.of("read_file"), visible.get(0));
        assertFalse(visible.get(0).contains("spawn_agent"));
        assertFalse(visible.get(0).contains("promote_goal"));
        assertFalse(visible.get(0).contains("dispatch_agents"));
        assertFalse(visible.get(0).contains("late_parent_tool"));
    }

    /** 子代理工具表已经裁剪成只读，工具本身也应声明只读：READ_ONLY 会话里不该被整体拒绝。 */
    @Test
    void readOnlySubagentPassesTheReadOnlyGate() {
        ToolRegistry parent = new ToolRegistry();
        parent.register(named("read_file"));
        SpawnAgentTool tool = new SpawnAgentTool((request, tools) -> new ModelResponse("ok", List.of()),
                parent, new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(8, 0, 4, "sys"), List::of, event -> { }, () -> false);

        assertTrue(tool.readOnly());
        assertTrue(new PolicyPermissionGate(PermissionMode.READ_ONLY)
                        .check(new ToolCall("call_1", "spawn_agent", Map.of("task", "只读盘点")), tool).isAllowed(),
                "只读会话应允许派生只读子代理");
    }

    @Test
    void exploreChildDoesNotReceiveWriteTools() {
        ToolRegistry parent = new ToolRegistry();
        parent.register(named("read_file"));
        parent.register(writing("edit_file"));
        parent.register(writing("run_command"));
        ArrayList<Set<String>> visible = new ArrayList<>();
        ModelClient client = (request, definitions) -> {
            visible.add(definitions.stream().map(ToolDefinition::name)
                    .collect(java.util.stream.Collectors.toSet()));
            return new ModelResponse("looked", List.of());
        };
        SpawnAgentTool tool = new SpawnAgentTool(client, parent,
                new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(),
                new AgentLoop.Config(8, 0, 4, "sys"), List::of, event -> { }, () -> false);

        tool.execute(Map.of("task", "find the entry"));

        assertEquals(Set.of("read_file"), visible.get(0));
    }

    @Test
    void usesIndependentEightStepBudgetAndDoesNotForwardChildCompletion() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(named("probe"));
        AtomicInteger requests = new AtomicInteger();
        ModelClient client = (request, definitions) -> {
            int current = requests.incrementAndGet();
            return new ModelResponse("", List.of(new ToolCall("call-" + current, "probe",
                    Map.of("round", current))));
        };
        ArrayList<SessionEvent> events = new ArrayList<>();
        SpawnAgentTool tool = new SpawnAgentTool(client, tools,
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(50, 0, 50, "sys"), List::of, events::add, () -> false);

        String result = tool.execute(Map.of("task", "bounded exploration"));

        assertTrue(result.startsWith("ERROR: 子代理未完成：MAX_STEPS"));
        assertEquals(9, requests.get(), "八次工作请求后仅允许 AgentLoop 的一次无工具收尾请求");
        assertTrue(events.stream().noneMatch(RunCompleted.class::isInstance),
                "子 run 的完成事件不能流入父任务状态机");
        assertTrue(events.stream().anyMatch(event -> event instanceof SubagentFinished finished && !finished.ok()));
    }

    @Test
    void returnsStructuredManifestWithoutForwardingChildRunCompleted() {
        ArrayList<SessionEvent> events = new ArrayList<>();
        SpawnAgentTool tool = new SpawnAgentTool(
                (request, definitions) -> new ModelResponse("done", List.of()),
                new ToolRegistry(), new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(20, 0, 10, "sys"), List::of, events::add, () -> false);

        String result = tool.execute(Map.of("task", "answer"));

        assertTrue(result.contains("<subagent_result>"));
        assertTrue(result.contains("changed_files: []"));
        assertTrue(events.stream().noneMatch(RunCompleted.class::isInstance));
    }

    @Test
    void failedChildStillReturnsStructuredChangesAndChecks() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override public String name() { return "child_work"; }
            @Override public String description() { return "child work"; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return true; }
            @Override public String execute(Map<String, Object> args) { return "changed"; }
            @Override public ToolResult executeResult(Map<String, Object> args) {
                return new ToolResult("changed", List.of("src/Child.java"),
                        List.of("mvn -q test → exit 0"));
            }
        });
        tools.register(named("probe"));
        AtomicInteger requests = new AtomicInteger();
        ModelClient client = (request, definitions) -> {
            int current = requests.incrementAndGet();
            String name = current == 1 ? "child_work" : "probe";
            return new ModelResponse("", List.of(new ToolCall("call-" + current, name,
                    Map.of("round", current))));
        };
        SpawnAgentTool tool = new SpawnAgentTool(client, tools,
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(50, 0, 50, "sys"), List::of, event -> { }, () -> false);

        ToolResult result = tool.executeResult(Map.of("task", "change then exhaust budget"));

        assertTrue(result.text().startsWith("ERROR: 子代理未完成：MAX_STEPS"));
        assertTrue(result.text().contains("changed_files: [src/Child.java]"));
        assertEquals(List.of("src/Child.java"), result.changedPaths());
        assertEquals(List.of("mvn -q test → exit 0"), result.checks());
    }

    @Test
    void capsTotalSpawnsAcrossOneParentRun() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient client = (request, definitions) -> {
            modelCalls.incrementAndGet();
            return new ModelResponse("done", List.of());
        };
        SpawnAgentTool tool = new SpawnAgentTool(client, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(20, 0, 10, "sys"), List::of, event -> { }, () -> false);

        for (int index = 0; index < 4; index++) {
            assertFalse(tool.execute(Map.of("task", "child-" + index)).startsWith("ERROR"));
        }
        String denied = tool.execute(Map.of("task", "child-5"));

        assertTrue(denied.contains("派生次数已达到 4 次"), denied);
        assertEquals(4, modelCalls.get(), "被 run 级池拒绝的子任务不能继续调用模型");
    }

    @Test
    void capsTotalChildRequestsAcrossDifferentSpawns() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(named("probe"));
        AtomicInteger actualCalls = new AtomicInteger();
        ModelClient client = (request, definitions) -> {
            int call = actualCalls.incrementAndGet();
            return new ModelResponse("", List.of(new ToolCall("call-" + call, "probe", Map.of())));
        };
        SpawnAgentTool tool = new SpawnAgentTool(client, tools,
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(50, 0, 50, "sys"), List::of, event -> { }, () -> false);

        tool.execute(Map.of("task", "first bounded child"));
        tool.execute(Map.of("task", "second bounded child"));
        String third = tool.execute(Map.of("task", "third bounded child"));

        assertEquals(24, actualCalls.get(), "所有 spawn 必须共享同一个父 run 请求池");
        assertTrue(third.startsWith("ERROR: 子代理未完成：MAX_STEPS"), third);
    }

    @Test
    void reportsEveryChildRequestUsageToParentSink() {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        AtomicLong inputTokens = new AtomicLong();
        SpawnAgentTool.UsageSink usage = new SpawnAgentTool.UsageSink() {
            @Override public void beforeRequest() { requests.incrementAndGet(); }
            @Override public void afterRequest(long activeMillis, ModelUsage modelUsage) {
                reports.incrementAndGet();
                inputTokens.addAndGet(modelUsage.inputTokens());
            }
        };
        ModelClient client = (request, definitions) -> new ModelResponse("done", List.of(),
                new ModelUsage(321, 12, 3, true));
        SpawnAgentTool tool = new SpawnAgentTool(client, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(20, 0, 10, "sys"), List::of, event -> { }, () -> false, usage);

        tool.execute(Map.of("task", "metered child"));

        assertEquals(1, requests.get());
        assertEquals(1, reports.get());
        assertEquals(321, inputTokens.get());
    }

    private static Tool writing(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return false; }
            @Override public String execute(Map<String, Object> args) { return "wrote"; }
        };
    }

    private static Tool named(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return true; }
            @Override public String execute(Map<String, Object> args) { return "ok"; }
        };
    }
}
