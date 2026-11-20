package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelPricing;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelRequestScope;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.RequestReport;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.PathValidator;

class CacheContextTest {
    @Test
    void batchesLowPressurePruningButEnforcesBudgetAtHighWater() {
        var messages = new ArrayList<Message>();
        messages.add(Message.system("stable"));
        messages.add(Message.user("x".repeat(15_000)));
        for (int i = 0; i < 8; i++) {
            messages.add(Message.assistant("", List.of(new ToolCall("c" + i, "read", Map.of()))));
            messages.add(Message.tool("c" + i, "x".repeat(5_000)));
        }
        var budget = new ContextBudget(
            (items, chars, tokens) -> ContextCompactor.Outcome.none(ContextCompactor.totalChars(items)));
        budget.apply(messages, 100_000, ignored -> {
        });
        var prefix = List.copyOf(messages);
        // 4k 新输出使旧工具超出保留窗口，但增长未满 5%，此时仍保留完整前缀。
        messages.add(Message.tool("new", "x".repeat(4_000)));
        budget.apply(messages, 100_000, ignored -> {
        });
        assertEquals(prefix, messages.subList(0, prefix.size()));
        // 上下文突增超过高水位后，立即执行本地预算保护。
        messages.add(Message.tool("large", "x".repeat(45_000)));
        budget.apply(messages, 100_000, ignored -> {
        });
        assertTrue(ContextCompactor.totalChars(messages) < 97_000);
        assertNotEquals(prefix, messages.subList(0, Math.min(prefix.size(), messages.size())));
    }

    @Test
    void changingTrustedTaskStateAppendsWithoutRewritingPreviousPrefix() {
        taskState(true);
    }

    @Test
    void genericEndpointsKeepTrustedStateInFirstSystemMessage() {
        taskState(false);
    }

    @Test
    void transientGuidanceUsesSystemAuthorityWithoutPollutingSavedHistory() {
        var requests = new ArrayList<List<Message>>();
        ModelClient model = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "test", "generic", "auto", "", true, null, "short", null);
            }

            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages));
                return requests.size() == 1
                    ? new ModelResponse("partial", List.of(), null, ModelResponse.FinishReason.LENGTH)
                    : new ModelResponse("done", List.of());
            }
        };
        var loop = new AgentLoop(model, new ToolRegistry(), new PolicyPermissionGate(PermissionMode.READ_ONLY),
            new AgentLoop.Config(5, 0, 5, "stable", List::of, 0, 100_000, false));
        var result = loop.run("task", List.of(), ignored -> {
        });
        assertNull(result.failureCode());
        assertEquals(2, requests.size());
        assertEquals("stable", requests.get(1).getFirst().content());
        assertEquals("stable", requests.get(1).getFirst().content());
        assertEquals("system", requests.get(1).getLast().role());
        assertTrue(requests.get(1).getLast().content().contains("Runtime guidance"));
        assertFalse(result.conversation().stream().anyMatch(m -> m.content().contains("Runtime guidance")));
    }

    private void taskState(boolean supported) {
        var requests = new ArrayList<List<Message>>();
        var state = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "test", "generic", "auto", "", supported, null, "short", null);
            }

            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages));
                return requests.size() < 3
                    ? new ModelResponse("", List.of(new ToolCall("call" + requests.size(), "read", Map.of())))
                    : new ModelResponse("done", List.of());
            }
        };
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String name() {
                return "read";
            }

            public String description() {
                return "read";
            }

            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return true;
            }

            public String execute(Map<String, Object> args) {
                return "result " + state.incrementAndGet();
            }
        });
        var config = new AgentLoop.Config(5, 0, 5, "stable-system", List::of, 0, 100_000, false);
        var loop = new AgentLoop(model, tools, new PolicyPermissionGate(PermissionMode.READ_ONLY), config)
            .withTaskContext(() -> "trusted state " + state.get());
        var result = loop.run("hello", List.of(), ignored -> {
        });
        assertNull(result.failureCode());
        assertEquals(3, requests.size());
        for (int i = 0; i < 3; i++) {
            int step = i;
            var request = requests.get(i);
            assertTrue(request.stream()
                .anyMatch(m -> m.role().equals("user") && m.content().contains("trusted state " + step)));
            assertEquals("stable-system", request.getFirst().content());
            if (i > 0) {
                assertEquals(requests.get(i - 1), request.subList(0, requests.get(i - 1).size()));
            }
        }
        assertTrue(requests.getLast().stream().anyMatch(m -> "call2".equals(m.toolCallId())));
    }

    @Test
    void optInCompactionReusesPrefixAndDisablesToolExecution() {
        compact(false, 100_000, true);
    }

    @Test
    void rejectedPrefixSummaryFallsBackToShortInputAndCountsBothAttempts() {
        compact(true, 100_000, true);
    }

    @Test
    void insufficientHeadroomUsesShortSummaryDirectly() {
        compact(false, 10000, false);
    }

    private void compact(boolean rejectPrefix, int limit, boolean prefixExpected) {
        List<Message> history = new ArrayList<>();
        history.add(Message.system("stable-system"));
        for (int i = 0; i < 12; i++) {
            history.add(Message.user("question " + i));
            history.add(Message.assistant("answer" + "x".repeat(2000), List.of()));
        }
        var original = List.copyOf(history);
        var requests = new ArrayList<List<Message>>();
        var reports = new ArrayList<RequestReport>();
        var definitions = List.of(new ToolDefinition("read", "read", Map.of("type", "object")));
        ModelClient model = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "test", "generic", "auto", "", false, false, "prefix",
                    new ModelPricing("USD", BigDecimal.ONE, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE,
                        "test"));
            }

            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages));
                assertEquals("compaction", ModelRequestScope.current().purpose());
                assertTrue(ModelRequestScope.current().toolsDisabled());
                if (messages.size() > 2) {
                    assertEquals(original, messages.subList(0, original.size()));
                    assertEquals(definitions, tools);
                    if (rejectPrefix) {
                        return new ModelResponse("invalid summary", List.of(new ToolCall("bad", "read", Map.of())));
                    }
                } else {
                    assertTrue(tools.isEmpty());
                }
                return new ModelResponse("交接摘要", List.of());
            }
        };
        var measured = new MeteredModelClient(model, "test", "main", List.of(), reports::add);
        var result = new ContextCompactor(measured, 2).withTools(() -> definitions).withContextLimit(() -> limit)
            .compact(history, 8000);
        assertTrue(result.compacted());
        assertEquals(prefixExpected, requests.getFirst().size() > 2);
        assertEquals(rejectPrefix && prefixExpected ? 2 : 1, reports.size());
        assertTrue(reports.stream().allMatch(r -> r.purpose().equals("compaction")));
        assertFalse(ModelRequestScope.current().toolsDisabled());
    }

    @Test
    void phaseHintStaysAtTailWithoutPollutingHistoryAndExpiresOnEditOrTurnCap() {
        var requests = new ArrayList<List<Message>>();
        ModelClient model = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "test", "generic", "auto", "", true, null, "short", null);
            }

            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages));
                return switch (requests.size()) {
                    case 1 -> new ModelResponse("", List.of(new ToolCall("c1", "plan_step", Map.of())));
                    case 2 -> new ModelResponse("", List.of(new ToolCall("c2", "write_step", Map.of())));
                    default -> new ModelResponse("done", List.of());
                };
            }
        };
        ToolRegistry tools = new ToolRegistry();
        tools.register(stubTool("plan_step", true, "planned", List.of()));
        tools.register(stubTool("write_step", false, "edited", List.of("App.java")));
        var config = new AgentLoop.Config(6, 0, 5, "stable-system", List::of, 0, 100_000, false);
        var loop = new AgentLoop(model, tools, new PolicyPermissionGate(PermissionMode.AUTO), config)
            .withInitialPhaseHint("<candidate-targets>\n- App.java\n</candidate-targets>");

        var result = loop.run("fix App", List.of(), ignored -> {
        });

        assertNull(result.failureCode());
        assertEquals(3, requests.size());
        // 第 1 步（调 plan）和第 2 步（调 write 前）都在尾部可见
        assertTrue(requests.get(0).getLast().content().contains("<candidate-targets>"));
        assertTrue(requests.get(1).getLast().content().contains("<candidate-targets>"));
        // 首次编辑成功后第 3 步自动退场
        assertFalse(requests.get(2).stream().anyMatch(m -> m.content().contains("<candidate-targets>")));
        // 不改写前缀且绝不进入持久化对话历史
        assertEquals("stable-system", requests.get(0).getFirst().content());
        assertEquals(requests.get(0).subList(0, requests.get(0).size() - 1),
            requests.get(1).subList(0, requests.get(0).size() - 1));
        assertFalse(result.conversation().stream().anyMatch(m -> m.content().contains("<candidate-targets>")));
    }

    private static Tool stubTool(String name, boolean readOnly, String output, List<String> changed) {
        return new Tool() {
            public String name() {
                return name;
            }

            public String description() {
                return name;
            }

            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return readOnly;
            }

            public String execute(Map<String, Object> args) {
                return output;
            }

            public ToolResult executeResult(Map<String, Object> args) {
                return new ToolResult(output, changed, List.of());
            }
        };
    }

    @Test
    void phaseHintClearsAfterCandidateFileIsReadSoNextTurnStaysAppendOnly(@org.junit.jupiter.api.io.TempDir Path dir)
        throws Exception {
        Files.writeString(dir.resolve("Target.java"), "class Target {}\n");
        var requests = new ArrayList<List<Message>>();
        ModelClient model = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "test", "generic", "auto", "", true, null, "short", null);
            }

            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                requests.add(List.copyOf(messages));
                return switch (requests.size()) {
                    case 1 ->
                        new ModelResponse("", List.of(new ToolCall("r1", "read_file", Map.of("path", "Target.java"))));
                    case 2 -> new ModelResponse("",
                        List.of(new ToolCall("r2", "read_file", Map.of("path", "Target.java", "start_line", 1))));
                    default -> new ModelResponse("done", List.of());
                };
            }
        };
        var tools = new ToolRegistry();
        tools.register(new ReadFileTool(new PathValidator(dir), new ReadTracker()));
        var config = new AgentLoop.Config(6, 0, 5, "stable-system", List::of, 0, 100_000, false);
        var loop = new AgentLoop(model, tools, new PolicyPermissionGate(PermissionMode.AUTO), config)
            .withInitialPhaseHint("<candidate-targets>\n- Target.java\n</candidate-targets>", Set.of("Target.java"));

        var result = loop.run("inspect Target", List.of(), ignored -> {
        });

        assertNull(result.failureCode());
        assertEquals(3, requests.size());
        assertTrue(requests.get(0).getLast().content().contains("<candidate-targets>"));
        assertFalse(requests.get(1).stream().anyMatch(m -> m.content().contains("<candidate-targets>")),
            "首轮已读取候选文件后，第 2 轮应清退尾部引导以保持严格 append_only");
        assertEquals(requests.get(1), requests.get(2).subList(0, requests.get(1).size()));
    }

    @Test
    void contextShrinkInvalidatesDeliveredReadViewsSoRereadSucceeds(@org.junit.jupiter.api.io.TempDir Path dir)
        throws Exception {
        Files.writeString(dir.resolve("Big.java"), "public class Big {\n" + "  // filler\n".repeat(120) + "}\n");
        var tracker = new ReadTracker();
        var reader = new ReadFileTool(new PathValidator(dir), tracker);
        var tools = new ToolRegistry();
        tools.register(reader);
        tools.register(new Tool() {
            public String name() {
                return "noisy";
            }

            public String description() {
                return "noisy";
            }

            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return true;
            }

            public String execute(Map<String, Object> args) {
                return "x".repeat(4500);
            }
        });
        var toolOutputs = new ArrayList<String>();
        var step = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "test", "generic", "auto", "", true, null, "short", null);
            }

            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                for (Message m : messages) {
                    if ("tool".equals(m.role()) && "r2".equals(m.toolCallId())) {
                        toolOutputs.add(m.content());
                    }
                }
                return switch (step.incrementAndGet()) {
                    case 1 ->
                        new ModelResponse("", List.of(new ToolCall("r1", "read_file", Map.of("path", "Big.java"))));
                    case 2 -> new ModelResponse("", List.of(new ToolCall("n1", "noisy", Map.of())));
                    case 3 -> new ModelResponse("", List.of(new ToolCall("n2", "noisy", Map.of())));
                    case 4 ->
                        new ModelResponse("", List.of(new ToolCall("r2", "read_file", Map.of("path", "Big.java"))));
                    default -> new ModelResponse("done", List.of());
                };
            }
        };
        // 小上下文预算促使 ContextBudget 在 step 3/4 裁剪旧工具输出并触发视图失效
        var config = new AgentLoop.Config(10, 0, 5, "stable", List::of, 0, 6_000, false);
        var loop = new AgentLoop(model, tools, new PolicyPermissionGate(PermissionMode.AUTO), config)
            .withContextShrinkListener(tracker::invalidateDeliveredViews);

        var result = loop.run("read twice across compaction", List.of(), ignored -> {
        });

        assertNull(result.failureCode());
        assertTrue(tracker.viewEpoch() > 0, "上下文收缩应触发已交付视图失效");
        assertFalse(toolOutputs.isEmpty());
        assertTrue(toolOutputs.getFirst().contains("public class Big"),
            "压缩后重读同一未变文件必须拿回正文，不能被「文件未变化」死锁: " + toolOutputs.getFirst());
    }
}
