package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;

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
}
