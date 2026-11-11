package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCancelled;

class PreviewMergeTest {
    private SessionService service(long millis, int bytes, int sessions) {
        return new SessionService(Clock.systemUTC(), new InMemorySessionRepository(), EventStreamLimits.defaults(),
            new PreviewLimits(millis, bytes, 131072, 16777216, sessions));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void burstRetainsTextAndFlushesBeforeDurableEvent() throws Exception {
        var service = service(10000, 8192, 256);
        var id = service.createSession("合并").id();
        var run = service.sendMessage(id, "开始").runId();
        var events = new CopyOnWriteArrayList<EventEnvelope>();
        try (var subscription = service.subscribe(id, 1, events::add)) {
            for (int i = 0; i < 1000; i++) {
                service.preview(id, run, new AssistantPreview("中🙂"));
            }
            service.publish(id, run, new AssistantDelta("完整"));
            await(() -> events.size() == 2);
            assertEquals("中🙂".repeat(1000), ((AssistantPreview)events.getFirst().payload()).text());
            assertEquals("assistant.delta", events.getLast().type());
            assertEquals(1000, service.streamMetrics().previewInputs());
            assertEquals(1, service.streamMetrics().previewOutputs());
            assertEquals(2, service.eventCount(id));
            assertEquals(0, service.streamMetrics().pendingPreviews());
        }
    }

    @Test
    void switchingKindsResetAndTerminalKeepOrder() throws Exception {
        var service = service(10000, 8192, 256);
        var id = service.createSession("顺序").id();
        var run = service.sendMessage(id, "开始").runId();
        var events = new CopyOnWriteArrayList<EventEnvelope>();
        try (var subscription = service.subscribe(id, 1, events::add)) {
            service.preview(id, run, new AssistantReasoning("推理"));
            service.preview(id, run, new AssistantPreview("旧"));
            service.preview(id, run, AssistantPreview.discarded());
            service.preview(id, run, new AssistantPreview("新"));
            service.publish(id, run, new RunCancelled("停止"));
            await(() -> events.size() == 5);
            assertEquals(List.of("assistant.reasoning", "assistant.preview", "assistant.preview", "assistant.preview",
                "run.cancelled"), events.stream().map(EventEnvelope::type).toList());
            assertTrue(((AssistantPreview)events.get(2).payload()).reset());
            assertEquals("新", ((AssistantPreview)events.get(3).payload()).text());
        }
    }

    @Test
    void timerFlushesAndUtf8ChunksDoNotSplitEmoji() throws Exception {
        var service = service(20, 8, 256);
        var id = service.createSession("字节").id();
        var run = service.sendMessage(id, "开始").runId();
        var events = new CopyOnWriteArrayList<EventEnvelope>();
        String input = "🙂中🙂文🙂结尾";
        try (var subscription = service.subscribe(id, 1, events::add)) {
            service.preview(id, run, new AssistantPreview(input));
            await(() -> events.stream().map(e -> ((AssistantPreview)e.payload()).text()).reduce("", String::concat)
                .equals(input));
            for (var event : events) {
                String text = ((AssistantPreview)event.payload()).text();
                assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 8);
                assertEquals(text, new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void pendingCapacityAndDeletionReleaseBuffers() throws Exception {
        var service = service(10000, 8192, 1);
        var a = service.createSession("A").id();
        var b = service.createSession("B").id();
        var ra = service.sendMessage(a, "开始").runId();
        var rb = service.sendMessage(b, "开始").runId();
        var events = new CopyOnWriteArrayList<EventEnvelope>();
        try (var sub = service.subscribe(b, 1, events::add)) {
            service.preview(a, ra, new AssistantPreview("缓冲"));
            service.preview(b, rb, new AssistantPreview("直接投递"));
            await(() -> events.size() == 1);
            assertEquals(1, service.streamMetrics().pendingPreviews());
            service.delete(a);
            assertEquals(0, service.streamMetrics().pendingPreviews());
            service.preview(a, ra, new AssistantPreview("迟到"));
            assertEquals(0, service.streamMetrics().pendingPreviews());
        }
    }
}
