package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;

import tools.jackson.databind.json.JsonMapper;

class ModelCapacityTest {
    @Test
    void reservesOutputAndMarginAndCountsImagesAndSchemas() {
        var capacity = new ModelCapacity(20000, 18000, 4000, 2000, 8192);
        assertEquals(14000, capacity.inputBudget());
        var images = List.of(new Attachment("a.png", "image/png", "/unused/a.png"),
            new Attachment("b.png", "image/png", "/unused/b.png"));
        assertFalse(RequestBudget.assess(List.of(Message.user("inspect", images)), List.of(), capacity).fits());
        assertFalse(RequestBudget.assess(List.of(Message.user("hi")),
            List.of(new ToolDefinition("large", "中文".repeat(8000), Map.of())), capacity).fits());
        assertThrows(IllegalArgumentException.class, () -> new ModelCapacity(100, 100, 80, 20, 1));
    }

    @Test
    void validatesCompleteToolBatchesAndRejectsOrphansBeforeSending() {
        var a = new ToolCall("a", "read", Map.of());
        var b = new ToolCall("b", "read", Map.of());
        RequestBudget.validateProtocol(
            List.of(Message.assistant("", List.of(a, b)), Message.tool("b", "B"), Message.tool("a", "A")));
        assertThrows(IllegalArgumentException.class,
            () -> RequestBudget.validateProtocol(List.of(Message.tool("a", "A"))));
        assertThrows(IllegalArgumentException.class, () -> RequestBudget
            .validateProtocol(List.of(Message.assistant("", List.of(a, b)), Message.tool("a", "A"))));
        assertThrows(IllegalArgumentException.class,
            () -> RequestBudget.validateProtocol(List.of(Message.assistant("", List.of(a, a)))));
    }

    @Test
    void chatWireSetsOutputLimitAndRejectsOversizeInEveryPurpose() {
        var raw = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(),
            URI.create("https://api.openai.com"), "unused", "alias");
        assertEquals(ModelCapacity.configured().outputTokens(),
            raw.requestBody(List.of(Message.user("hi")), List.of(), false).get("max_completion_tokens"));
        var tooLarge = List.of(Message.user("中".repeat(150000)));
        for (String purpose : List.of("main", "compaction", "review", "finalization", "subagent")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> ModelRequestScope.within(purpose,
                "session", false, () -> raw.requestBody(tooLarge, List.of(), false)));
            assertTrue(failure.getMessage().contains("CONTEXT_TOO_LARGE"));
        }
    }
}
