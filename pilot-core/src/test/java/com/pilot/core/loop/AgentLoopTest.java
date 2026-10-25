package com.pilot.core.loop;

import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PermissionResult;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    private final List<SessionEvent> events = new ArrayList<>();

    @Test
    void executesToolCallThenReturnsFinalAnswer() {
        var tool = new CountingTool("list_dir", "a.txt\nsrc/");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "list_dir", Map.of("path", ".")))),
                new ModelResponse("工作区有 a.txt 和 src 目录。", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("列出目录", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals("工作区有 a.txt 和 src 目录。", result.finalText());
        assertEquals(1, tool.executions.get());
        assertEquals(List.of("tool.started", "tool.completed", "assistant.delta", "run.completed"),
                eventTypes());

        List<Message> secondRequest = client.requests.get(1);
        Message toolMessage = secondRequest.get(secondRequest.size() - 1);
        assertEquals("tool", toolMessage.role());
        assertEquals("call_1", toolMessage.toolCallId());
        assertEquals("a.txt\nsrc/", toolMessage.content());
        assertTrue(secondRequest.get(secondRequest.size() - 2).hasToolCalls());
    }

    @Test
    void sendsSystemPromptUserInputAndToolDefinitions() {
        var tool = new CountingTool("read_file", "content");
        var client = new ScriptedModelClient(new ModelResponse("不需要工具", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        loop.run("读 pom.xml", List.of(), events::add);

        List<Message> firstRequest = client.requests.get(0);
        assertEquals("system", firstRequest.get(0).role());
        assertEquals("user", firstRequest.get(1).role());
        assertEquals("读 pom.xml", firstRequest.get(1).content());
        assertEquals(List.of("read_file"), client.toolDefinitions.get(0).stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("assistant.delta", "run.completed"), eventTypes());
    }

    @Test
    void interruptsAfterThreeIdenticalToolCalls() {
        var tool = new CountingTool("read_file", "content");
        var call = new ToolCall("call_1", "read_file", Map.of("path", "pom.xml"));
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(call)),
                new ModelResponse("", List.of(call)),
                new ModelResponse("", List.of(call)),
                new ModelResponse("不该走到这里", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("读 pom.xml", List.of(), events::add);

        assertEquals("REPEATED_TOOL_CALL", result.failureCode());
        assertEquals(2, tool.executions.get());
        assertEquals(3, client.requests.size());
        assertTrue(events.stream().anyMatch(event -> event instanceof RunFailed failed
                && failed.code().equals("REPEATED_TOOL_CALL")));
        assertFalse(eventTypes().contains("run.completed"));
    }

    @Test
    void stopsAtMaxSteps() {
        var tool = new CountingTool("read_file", "content");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "a")))),
                new ModelResponse("", List.of(new ToolCall("call_2", "read_file", Map.of("path", "b")))),
                new ModelResponse("done", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(2, 3));

        AgentLoop.Result result = loop.run("读两个文件", List.of(), events::add);

        assertEquals("MAX_STEPS", result.failureCode());
        assertEquals(2, client.requests.size());
        assertEquals(2, tool.executions.get());
    }

    @Test
    void deniedToolIsReportedWithoutExecution() {
        var tool = new CountingTool("write_file", "written");
        PermissionGate denyAll = (call, candidate) -> PermissionResult.deny("Sprint 2 只支持只读");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "write_file", Map.of("path", "a.txt")))),
                new ModelResponse("好的，我不写文件。", List.of()));
        var loop = new AgentLoop(client, registry(tool), denyAll, config(8, 3));

        AgentLoop.Result result = loop.run("写个文件", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(0, tool.executions.get());
        List<Message> secondRequest = client.requests.get(1);
        String toolResult = secondRequest.get(secondRequest.size() - 1).content();
        assertTrue(toolResult.startsWith("ERROR: 权限拒绝"), toolResult);
        ToolCompleted completed = (ToolCompleted) events.stream()
                .filter(event -> event instanceof ToolCompleted).findFirst().orElseThrow();
        assertTrue(completed.resultSummary().contains("权限拒绝"), completed.resultSummary());
    }

    @Test
    void unknownToolIsReportedToModel() {
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "drop_database", Map.of()))),
                new ModelResponse("工具不存在。", List.of()));
        var loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("删库", List.of(), events::add);

        assertNull(result.failureCode());
        String toolResult = client.requests.get(1).getLast().content();
        assertTrue(toolResult.contains("未知工具 drop_database"), toolResult);
    }

    @Test
    void modelFailureEndsRun() {
        ModelClient failing = (messages, tools) -> {
            throw new ModelClientException("HTTP 503");
        };
        var loop = new AgentLoop(failing, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertEquals("MODEL_ERROR", result.failureCode());
        assertEquals(List.of("run.failed"), eventTypes());
        RunFailed failed = (RunFailed) events.getFirst();
        assertTrue(failed.message().contains("HTTP 503"), failed.message());
        assertTrue(failed.retryable());
    }

    private List<String> eventTypes() {
        return events.stream().map(event -> event.type().wireName()).toList();
    }

    private static AgentLoop.Config config(int maxSteps, int repeatedLimit) {
        return new AgentLoop.Config(maxSteps, repeatedLimit, "你是测试代理");
    }

    private static PolicyPermissionGate readOnlyGate() {
        return new PolicyPermissionGate(PermissionMode.READ_ONLY);
    }

    private static ToolRegistry registry(Tool... tools) {
        var registry = new ToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static final class ScriptedModelClient implements ModelClient {
        private final Deque<ModelResponse> script = new ArrayDeque<>();
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> toolDefinitions = new ArrayList<>();

        private ScriptedModelClient(ModelResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            requests.add(List.copyOf(messages));
            toolDefinitions.add(List.copyOf(tools));
            ModelResponse next = script.poll();
            if (next == null) {
                throw new IllegalStateException("脚本已用完");
            }
            return next;
        }
    }

    private static final class CountingTool implements Tool {
        private final String name;
        private final String result;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public boolean readOnly() {
            return name.startsWith("read") || name.startsWith("list") || name.startsWith("search");
        }

        @Override
        public String execute(Map<String, Object> args) {
            executions.incrementAndGet();
            return result;
        }
    }
}
