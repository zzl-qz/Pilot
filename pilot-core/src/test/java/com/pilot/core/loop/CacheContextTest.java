package com.pilot.core.loop;

import com.pilot.core.model.*;
import com.pilot.core.permission.*;
import com.pilot.core.tool.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CacheContextTest {
    @Test void batchesLowPressurePruningButEnforcesBudgetAtHighWater() {
        var messages = new ArrayList<Message>(); messages.add(Message.system("stable"));
        messages.add(Message.user("x".repeat(15_000)));
        for (int i = 0; i < 8; i++) {
            messages.add(Message.assistant("", List.of(new ToolCall("c" + i, "read", Map.of()))));
            messages.add(Message.tool("c" + i, "x".repeat(5_000)));
        }
        var budget = new ContextBudget((items, chars, tokens) -> ContextCompactor.Outcome.none(ContextCompactor.totalChars(items)));
        budget.apply(messages, 100_000, ignored -> {});
        var prefix = List.copyOf(messages);
        // 4k 新输出使旧工具超出保留窗口，但增长未满 5%，此时仍保留完整前缀。
        messages.add(Message.tool("new", "x".repeat(4_000)));
        budget.apply(messages, 100_000, ignored -> {});
        assertEquals(prefix, messages.subList(0, prefix.size()));
        // 上下文突增超过高水位后，立即执行本地预算保护。
        messages.add(Message.tool("large", "x".repeat(45_000)));
        budget.apply(messages, 100_000, ignored -> {});
        assertTrue(ContextCompactor.totalChars(messages) < 97_000);
        assertNotEquals(prefix, messages.subList(0, Math.min(prefix.size(), messages.size())));
    }
    @Test void changingTrustedTaskStateAppendsWithoutRewritingPreviousPrefix() { taskState(true); }
    @Test void genericEndpointsKeepTrustedStateInFirstSystemMessage() { taskState(false); }
    @Test void transientGuidanceUsesSystemAuthorityWithoutPollutingSavedHistory() {
        var requests = new ArrayList<List<Message>>();
        ModelClient model = new ModelClient() {
            public ModelProfile profile() { return ModelProfile.resolve(null, "test", "generic", "auto", "", true, null, "short", null); }
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages));
                return requests.size() == 1 ? new ModelResponse("partial", List.of(), null, ModelResponse.FinishReason.LENGTH)
                        : new ModelResponse("done", List.of());
            }
        };
        var loop = new AgentLoop(model, new ToolRegistry(), new PolicyPermissionGate(PermissionMode.READ_ONLY),
                new AgentLoop.Config(5, 0, 5, "stable", List::of, 0, 100_000, false));
        var result = loop.run("task", List.of(), ignored -> {});
        assertNull(result.failureCode()); assertEquals(2, requests.size());
        assertEquals("stable", requests.get(1).getFirst().content());
        assertEquals("system", requests.get(1).getLast().role());
        assertTrue(requests.get(1).getLast().content().contains("Runtime guidance"));
        assertFalse(result.conversation().stream().anyMatch(m -> m.content().contains("Runtime guidance")));
    }
    private void taskState(boolean supported) {
        var requests = new ArrayList<List<Message>>(); var state = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public ModelProfile profile() { return ModelProfile.resolve(null, "test", "generic", "auto", "", supported, null, "short", null); }
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages));
                return requests.size() < 3 ? new ModelResponse("", List.of(new ToolCall("call" + requests.size(), "read", Map.of()))) : new ModelResponse("done", List.of());
            }
        };
        ToolRegistry tools = new ToolRegistry(); tools.register(new Tool() {
            public String name() { return "read"; } public String description() { return "read"; }
            public Map<String,Object> parameters() { return Map.of("type", "object"); }
            public boolean readOnly() { return true; }
            public String execute(Map<String,Object> args) { return "result " + state.incrementAndGet(); }
        });
        var config = new AgentLoop.Config(5, 0, 5, "stable-system", List::of, 0, 100_000, false);
        var loop = new AgentLoop(model, tools, new PolicyPermissionGate(PermissionMode.READ_ONLY), config)
                .withTaskContext(() -> "trusted state " + state.get());
        var result = loop.run("hello", List.of(), ignored -> {});
        assertNull(result.failureCode()); assertEquals(3, requests.size());
        for (int i = 0; i < 3; i++) {
            int step = i;
            var request = requests.get(i);
            assertTrue(request.stream().anyMatch(m -> m.role().equals("system") && m.content().contains("trusted state " + step)));
            if (supported) {
                assertEquals("stable-system", request.getFirst().content());
                if (i > 0) assertEquals(requests.get(i - 1), request.subList(0, requests.get(i - 1).size()));
            } else {
                assertTrue(request.getFirst().content().contains("trusted state " + i));
                assertEquals(1, request.stream().filter(m -> m.role().equals("system")).count());
            }
        }
        assertTrue(requests.getLast().stream().anyMatch(m -> "call2".equals(m.toolCallId())));
    }
    @Test void optInCompactionReusesPrefixAndDisablesToolExecution() { compact(false, 100_000, true); }
    @Test void rejectedPrefixSummaryFallsBackToShortInputAndCountsBothAttempts() { compact(true, 100_000, true); }
    @Test void insufficientHeadroomUsesShortSummaryDirectly() { compact(false, 100, false); }
    private void compact(boolean rejectPrefix, int limit, boolean prefixExpected) {
        List<Message> history = new ArrayList<>(); history.add(Message.system("stable-system"));
        for (int i = 0; i < 12; i++) { history.add(Message.user("question " + i + "x".repeat(2000))); history.add(Message.assistant("answer", List.of())); }
        var original = List.copyOf(history); var requests = new ArrayList<List<Message>>(); var reports = new ArrayList<RequestReport>();
        var definitions = List.of(new ToolDefinition("read", "read", Map.of("type", "object")));
        ModelClient model = new ModelClient() {
            public ModelProfile profile() { return ModelProfile.resolve(null, "test", "generic", "auto", "", false, false, "prefix",
                    new ModelPricing("USD", BigDecimal.ONE, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, "test")); }
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages)); assertEquals("compaction", ModelRequestScope.current().purpose()); assertTrue(ModelRequestScope.current().toolsDisabled());
                if (messages.size() > 2) {
                    assertEquals(original, messages.subList(0, original.size())); assertEquals(definitions, tools);
                    if (rejectPrefix) return new ModelResponse("invalid summary", List.of(new ToolCall("bad", "read", Map.of())));
                } else assertTrue(tools.isEmpty());
                return new ModelResponse("交接摘要", List.of());
            }
        };
        var measured = new MeteredModelClient(model, "test", "main", List.of(), reports::add);
        var result = new ContextCompactor(measured, 2).withTools(() -> definitions).withContextLimit(() -> limit).compact(history, 8000);
        assertTrue(result.compacted()); assertEquals(prefixExpected, requests.getFirst().size() > 2);
        assertEquals(rejectPrefix && prefixExpected ? 2 : 1, reports.size());
        assertTrue(reports.stream().allMatch(r -> r.purpose().equals("compaction")));
        assertFalse(ModelRequestScope.current().toolsDisabled());
    }
}
