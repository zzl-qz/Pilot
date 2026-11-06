package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.RequestBudget;
import com.pilot.core.tool.ToolCall;

class ContextIntegrityTest {
    @Test
    void protectsEveryUnobservedResultAcrossAllBudgetLayers() {
        List<Message> messages = new ArrayList<>(List.of(Message.system("system"), Message.user("原始目标"),
            Message.assistant("",
                List.of(new ToolCall("a", "read_file", Map.of()), new ToolCall("b", "read_file", Map.of()),
                    new ToolCall("c", "read_file", Map.of()))),
            Message.tool("a", "A".repeat(30000)), Message.tool("b", "B".repeat(30000)),
            Message.tool("c", "C".repeat(30000))));
        var budget = new ContextBudget((m, c, t) -> ContextCompactor.Outcome.none(ContextCompactor.totalChars(m)));
        budget.apply(messages, 40000, e -> {
        }, 2);
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                assertEquals(30000, message.content().length());
            }
        }
        RequestBudget.validateProtocol(messages);
    }

    @Test
    void preservesUserGoalVerbatimThroughRepeatedCompactionEvenWhenSummaryOmitsIt() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("system"));
        messages.add(Message.user("USER_GOAL_所有功能都要完成，不只修改文档"));
        for (int i = 0; i < 90; i++) {
            messages.add(Message.assistant("观察" + "x".repeat(2000), List.of()));
        }
        var compactor = new ContextCompactor((m, t) -> new ModelResponse("没有保留目标的模型摘要", List.of()));
        assertTrue(compactor.compact(messages, 20000).compacted());
        assertTrue(messages.get(1).content().contains("USER_GOAL_所有功能都要完成，不只修改文档"));
        assertTrue(messages.get(1).content().contains("source_sha256"));
        for (int i = 0; i < 40; i++) {
            messages.add(Message.assistant("y".repeat(2000), List.of()));
        }
        assertTrue(compactor.compact(messages, 20000).compacted());
        assertTrue(messages.get(1).content().contains("USER_GOAL_所有功能都要完成，不只修改文档"));
    }

    @Test
    void archiveFailureDoesNotDestroyOnlyCopyAndArchiveReferenceSurvivesPruning() {
        List<Message> messages =
            new ArrayList<>(List.of(Message.system("s"), Message.tool("lost", "x".repeat(12000) + "\n[工具输出未归档，禁止清理原文]"),
                Message.tool("saved", "y".repeat(12000) + "\n[工具输出存档 run_id=r call_id=saved；使用 read_tool_output]"),
                Message.user("next")));
        ContextCompactor.pruneToolOutputs(messages, 100, 3);
        ContextCompactor.truncateLongToolOutputs(messages, 3);
        assertTrue(messages.get(1).content().length() > 12000);
        assertTrue(messages.get(2).content().contains("run_id=r call_id=saved"));
    }

    @Test
    void repeatedMetadataMergeDoesNotRecursivelyCopySerializedReferences() {
        String reference = "[工具输出存档 run_id=r call_id=c；使用 read_tool_output]";
        String metadata = ContextPins.capture(
            List.of(Message.system("s"), Message.user("最初任务"), Message.tool("c", "result" + reference)), 3, "hash");
        int initialLength = metadata.length();
        for (int i = 0; i < 10; i++) {
            metadata = ContextPins.capture(List.of(Message.system("s"), Message.user("[上下文交接摘要]summary" + metadata)), 2,
                "hash");
            assertEquals(initialLength, metadata.length());
        }
        String malformed = "[上下文交接摘要]\n<pilot_context_metadata>{bad}</pilot_context_metadata>";
        assertTrue(
            ContextPins.capture(List.of(Message.system("s"), Message.user(malformed)), 2, "hash").contains("{bad}"));
    }
}
