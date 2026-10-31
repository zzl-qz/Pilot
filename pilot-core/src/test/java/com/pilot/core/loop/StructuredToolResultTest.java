package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuredToolResultTest {

    @Test
    void toolExecutorCarriesEveryStructuredPathAndCheck() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(structuredTool());
        ToolExecutor executor = new ToolExecutor(tools,
                new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll());

        ToolExecutor.Outcome outcome = executor.execute(
                new ToolCall("call-1", "structured_work", Map.of()), ignored -> { });

        assertEquals(List.of("src/A.java", "src/B.java"), outcome.changedPaths());
        assertEquals(List.of("mvn test → exit 0", "npm test → exit 0"), outcome.checks());
        assertEquals("src/A.java", outcome.changedPath(), "旧的单值访问器仍须兼容");
    }

    @Test
    void agentLoopMergesStructuredArtifactsIntoCompletionFacts() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(structuredTool());
        AtomicInteger request = new AtomicInteger();
        ModelClient model = (messages, definitions) -> request.getAndIncrement() == 0
                ? new ModelResponse("", List.of(new ToolCall("call-1", "structured_work", Map.of())))
                : new ModelResponse("完成", List.of());
        ArrayList<SessionEvent> events = new ArrayList<>();
        AgentLoop.Config config = new AgentLoop.Config(4, 0, 3, "sys", List::of, 0, 20_000, false);

        new AgentLoop(model, tools, new PolicyPermissionGate(PermissionMode.AUTO), config)
                .run("完成结构化子任务", List.of(), events::add);

        RunCompleted completed = events.stream().filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("src/A.java", "src/B.java"), completed.changedFiles());
        assertEquals(List.of("mvn test → exit 0", "npm test → exit 0"), completed.checks());
    }

    private static Tool structuredTool() {
        return new Tool() {
            @Override public String name() { return "structured_work"; }
            @Override public String description() { return "structured work"; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return false; }
            @Override public String execute(Map<String, Object> args) { return "done"; }
            @Override public ToolResult executeResult(Map<String, Object> args) {
                return new ToolResult("done", List.of("src/A.java", "src/B.java"),
                        List.of("mvn test → exit 0", "npm test → exit 0"));
            }
        };
    }
}
