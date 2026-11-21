package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;

class RequestBudgetTest {
    @Test
    void includesToolDefinitionsAndStructuredCalls() {
        List<Message> messages = List.of(Message.user("hello"),
            Message.assistant("", List.of(new ToolCall("c1", "read_file", Map.of("path", "路径".repeat(500))))));
        ToolDefinition tool = new ToolDefinition("read_file", "描述".repeat(500), Map.of("type", "object"));

        assertFalse(RequestBudget.assess(messages, List.of(tool), 1_000).fits());
    }

    @Test
    void allowsSmallRequestBelowSafetyInputWatermark() {
        assertTrue(RequestBudget.assess(List.of(Message.user("hello")), List.of(), 1_000).fits());
    }

    @Test
    void includesReasoningMetadataInContextEstimates() {
        String reasoning = "内部思考".repeat(100);
        Message withoutReasoning = Message.assistant("", List.of());
        Message withReasoning = Message.assistant("", List.of(), reasoning);

        assertEquals(TokenEstimate.of(reasoning),
            ContextComposition.estimate(List.of(withReasoning), List.of(), List.of()).totalTokens()
                - ContextComposition.estimate(List.of(withoutReasoning), List.of(), List.of()).totalTokens());
        assertEquals(TokenEstimate.of(reasoning), TokenEstimate.of(List.of(withReasoning)));
    }

    @Test
    void safetyMarginIsAppliedOnceAgainstTheActiveModel() {
        ModelCapacity.activate(ModelCapacity.of(32_000L, 4_000));
        try {
            RequestBudget.Assessment assessment =
                RequestBudget.assessRequest(List.of(Message.user("你好")), List.of(), 2_000_000);
            assertEquals(20_000L, assessment.inputLimit());
        } finally {
            ModelCapacity.activate(null);
        }
    }
}
