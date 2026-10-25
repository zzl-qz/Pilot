package com.pilot.core.event;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventEnvelopeTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void serializesAllEnvelopeFields() {
        var envelope = EventEnvelope.of(7, Instant.parse("2026-10-25T10:15:30Z"), "ses_1", "run_1",
                new AssistantDelta("你好"));

        JsonNode node = json.readTree(json.writeValueAsString(envelope));

        assertEquals(7, node.get("sequence").asLong());
        assertEquals("2026-10-25T10:15:30Z", node.get("timestamp").asText());
        assertEquals("assistant.delta", node.get("type").asText());
        assertEquals("ses_1", node.get("sessionId").asText());
        assertEquals("run_1", node.get("runId").asText());
        assertEquals("你好", node.get("payload").get("text").asText());
        assertEquals(1, node.get("version").asInt());
    }

    @Test
    void derivesTypeFromPayload() {
        var envelope = EventEnvelope.of(1, Instant.parse("2026-10-25T10:15:30Z"), "ses_1", "run_1",
                new ToolCompleted("call_1", "ok", 12L));

        assertEquals("tool.completed", envelope.type());
    }

    @Test
    void rejectsSequenceBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
                0, Instant.parse("2026-10-25T10:15:30Z"), "run.started", "ses_1", "run_1",
                new RunStarted("hi")));
    }

    @Test
    void rejectsUnsupportedVersionAndMismatchedType() {
        var timestamp = Instant.parse("2026-10-25T10:15:30Z");
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
                1, timestamp, "run.started", "ses_1", "run_1", new RunStarted("hi"), 0));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
                1, timestamp, "assistant.delta", "ses_1", "run_1", new RunStarted("hi"), 1));
    }

    @Test
    void rejectsMissingSessionId() {
        assertThrows(IllegalArgumentException.class, () -> EventEnvelope.of(
                1, Instant.parse("2026-10-25T10:15:30Z"), " ", "run_1", new RunStarted("hi")));
    }
}
