package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnAgentToolTest {

    @Test
    void returnsSubagentConclusionAndEmitsEvents() {
        ModelClient client = (request, tools) -> new ModelResponse("有 3 个入口类：A、B、C", List.of());
        var events = new ArrayList<SessionEvent>();
        var tool = new SpawnAgentTool(client, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "你是测试代理"), List::of, events::add, () -> false);

        String result = tool.execute(Map.of("task", "盘点入口类"));

        assertEquals("有 3 个入口类：A、B、C", result);
        assertTrue(events.get(0) instanceof SubagentStarted started && started.task().contains("盘点入口类"));
        assertTrue(events.stream().anyMatch(event -> event instanceof SubagentFinished finished && finished.ok()),
                "子代理结束事件必须带成功标记");
    }

    @Test
    void rejectsBlankTask() {
        ModelClient client = (request, tools) -> new ModelResponse("不该被调用", List.of());
        var tool = new SpawnAgentTool(client, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "sys"), List::of, event -> { }, () -> false);

        assertTrue(tool.execute(Map.of("task", "  ")).startsWith("ERROR"));
    }

    @Test
    void inheritsHistoryOnlyWhenRequested() {
        var seen = new ArrayList<List<Message>>();
        ModelClient client = (request, tools) -> new ModelResponse("ok", List.of());
        ModelClient recording = (request, tools) -> {
            seen.add(request);
            return client.chat(request, tools);
        };
        var history = List.of(Message.user("历史里的约束"), Message.assistant("收到", List.of()));
        var tool = new SpawnAgentTool(recording, new ToolRegistry(),
                new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
                new AgentLoop.Config(5, 3, "sys"), () -> history, event -> { }, () -> false);

        tool.execute(Map.of("task", "只做子任务"));
        tool.execute(Map.of("task", "带着历史做", "fork_turns", "all"));

        assertTrue(seen.get(0).stream().noneMatch(message -> message.content().contains("历史里的约束")),
                "默认不继承父对话");
        assertTrue(seen.get(1).stream().anyMatch(message -> message.content().contains("历史里的约束")),
                "fork_turns=all 时应带上父对话");
    }
}
