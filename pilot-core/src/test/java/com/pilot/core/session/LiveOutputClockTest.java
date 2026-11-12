package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.SessionEvent;

class LiveOutputClockTest {
    private final LiveOutputCache cache = new LiveOutputCache(PreviewLimits.defaults());
    private final Instant start = Instant.parse("2026-11-12T00:00:00Z");

    private void emit(long seconds, SessionEvent event) {
        cache.update(EventEnvelope.of(1, start.plusSeconds(seconds), "session", "run", event));
    }

    private LiveOutput snapshot() {
        return cache.snapshot("session", "run", 1, 0, true);
    }

    @Test
    void reconnectRetryAndCommitPreserveClockAndNewReasoningResumesIt() {
        cache.begin("session", "run");
        emit(0, new AssistantReasoning("开始"));
        emit(30, new AssistantReasoning("继续"));
        assertEquals(start, snapshot().reasoningStartedAt());
        assertNull(snapshot().reasoningFinishedAt());
        emit(40, AssistantReasoning.discarded());
        emit(50, new AssistantReasoning("重试"));
        assertEquals(start, snapshot().reasoningStartedAt());
        emit(60, new AssistantPreview("正文"));
        emit(70, new AssistantPreview("继续正文"));
        assertEquals(start.plusSeconds(60), snapshot().reasoningFinishedAt());
        String previewId = snapshot().previewId();
        cache.commit("session", "run", start.plusSeconds(80));
        assertNotEquals(previewId, snapshot().previewId());
        assertEquals("", snapshot().reasoning());
        assertEquals(start, snapshot().reasoningStartedAt());
        assertEquals(start.plusSeconds(60), snapshot().reasoningFinishedAt());
        emit(90, new AssistantReasoning("下一步"));
        assertEquals(start, snapshot().reasoningStartedAt());
        assertNull(snapshot().reasoningFinishedAt());
        cache.commit("session", "run", start.plusSeconds(100));
        assertEquals(start.plusSeconds(100), snapshot().reasoningFinishedAt());
    }

    @Test
    void emptyChunksDoNotStartClockAndRunsSessionsAndDeletionAreIsolated() {
        cache.begin("session", "run");
        emit(0, new AssistantReasoning(""));
        assertNull(snapshot().reasoningStartedAt());
        emit(1, new AssistantReasoning("思考"));
        emit(2, new AssistantPreview(""));
        assertNull(snapshot().reasoningFinishedAt());
        cache.begin("other", "run");
        assertNull(cache.snapshot("other", "run", 1, 0, true).reasoningStartedAt());
        cache.begin("session", "new-run");
        emit(10, new AssistantReasoning("旧回调"));
        assertNull(snapshot().reasoningStartedAt());
        cache.clear("session");
        assertNull(snapshot().reasoningStartedAt());
        assertEquals(0, cache.bytes());
    }
}
