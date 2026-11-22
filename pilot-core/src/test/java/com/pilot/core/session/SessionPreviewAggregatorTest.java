package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.EventType;

class SessionPreviewAggregatorTest {
    private static final Instant ARRIVED = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void mergesSameKindAndKeepsFirstSequenceAndTimestamp() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var aggregator = new SessionPreviewAggregator(new PreviewLimits(10_000, 8, 128, 256, 4), scheduler);
            var outputs = new ArrayList<SessionPreviewAggregator.PreviewOutput>();
            var sequenceReads = new AtomicInteger();
            var first = aggregator.accept(input("run", EventType.ASSISTANT_PREVIEW, "ab", false, ARRIVED),
                sequenceReads::incrementAndGet, outputs::add, Runnable::run);
            var second = aggregator.accept(input("run", EventType.ASSISTANT_PREVIEW, "cd", false,
                ARRIVED.plusSeconds(1)), sequenceReads::incrementAndGet, outputs::add, Runnable::run);

            assertTrue(first.accepted());
            assertEquals(1, first.pendingSessions());
            assertTrue(second.accepted());
            assertEquals(1, second.pendingSessions());
            assertEquals(1, sequenceReads.get());
            assertEquals(0, aggregator.finish("session", outputs::add, false));
            assertEquals(1, outputs.size());
            assertEquals("abcd", outputs.getFirst().text());
            assertEquals(1, outputs.getFirst().sequence());
            assertEquals(ARRIVED, outputs.getFirst().timestamp());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void rejectsLateRunAndFlushesKindChangeAndResetInOrder() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var aggregator = new SessionPreviewAggregator(new PreviewLimits(10_000, 8, 128, 256, 4), scheduler);
            var outputs = new ArrayList<SessionPreviewAggregator.PreviewOutput>();
            aggregator.accept(input("run-current", EventType.ASSISTANT_PREVIEW, "body", false, ARRIVED), () -> 5,
                outputs::add, Runnable::run);
            var stale = aggregator.accept(input("run-old", EventType.ASSISTANT_PREVIEW, "late", false, ARRIVED),
                () -> {
                    throw new AssertionError("stale callback must not query current sequence");
                }, outputs::add, Runnable::run);
            assertFalse(stale.accepted());

            aggregator.accept(input("run-current", EventType.ASSISTANT_REASONING, "reason", false, ARRIVED),
                () -> 6, outputs::add, Runnable::run);
            aggregator.accept(input("run-current", EventType.ASSISTANT_REASONING, "", true, ARRIVED), () -> 7,
                outputs::add, Runnable::run);

            assertEquals(3, outputs.size());
            assertEquals("body", outputs.get(0).text());
            assertEquals(EventType.ASSISTANT_PREVIEW, outputs.get(0).type());
            assertEquals("reason", outputs.get(1).text());
            assertEquals(6, outputs.get(1).sequence());
            assertTrue(outputs.get(2).reset());
            assertEquals(0, aggregator.pendingSessions());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void respectsSessionAndUtf8ByteLimits() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var aggregator = new SessionPreviewAggregator(new PreviewLimits(10_000, 8, 128, 256, 1), scheduler);
            var outputs = new ArrayList<SessionPreviewAggregator.PreviewOutput>();
            aggregator.accept(input("run-a", EventType.ASSISTANT_PREVIEW, "🙂中🙂", false, ARRIVED), () -> 1,
                outputs::add, Runnable::run);
            aggregator.accept(inputFor("other", "run-b", "overflow"), () -> 2, outputs::add, Runnable::run);

            assertEquals(List.of("🙂中", "overflow"), outputs.stream()
                .map(SessionPreviewAggregator.PreviewOutput::text).toList());
            aggregator.finish("session", outputs::add, false);
            assertEquals(List.of("🙂中", "overflow", "🙂"), outputs.stream()
                .map(SessionPreviewAggregator.PreviewOutput::text).toList());
            assertTrue(outputs.stream().allMatch(output ->
                output.text().getBytes(StandardCharsets.UTF_8).length <= 8));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void scheduledFlushEmitsWithoutSessionService() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var aggregator = new SessionPreviewAggregator(new PreviewLimits(20, 8, 128, 256, 4), scheduler);
            var outputs = new ArrayList<SessionPreviewAggregator.PreviewOutput>();
            var flushed = new CountDownLatch(1);
            aggregator.accept(input("run", EventType.ASSISTANT_PREVIEW, "timer", false, ARRIVED), () -> 1,
                outputs::add, task -> {
                    task.run();
                    flushed.countDown();
                });

            assertTrue(flushed.await(2, TimeUnit.SECONDS));
            assertEquals("timer", outputs.getFirst().text());
            assertEquals(0, aggregator.pendingSessions());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void staleTimerDoesNotFlushReplacementPreview() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var aggregator = new SessionPreviewAggregator(new PreviewLimits(20, 8, 128, 256, 4), scheduler);
            var outputs = new ArrayList<SessionPreviewAggregator.PreviewOutput>();
            var scheduled = new CountDownLatch(1);
            var timerTasks = new CopyOnWriteArrayList<Runnable>();
            aggregator.accept(input("run", EventType.ASSISTANT_PREVIEW, "first", false, ARRIVED), () -> 1,
                outputs::add, task -> {
                    timerTasks.add(task);
                    scheduled.countDown();
                });

            assertTrue(scheduled.await(2, TimeUnit.SECONDS));
            aggregator.accept(input("run", EventType.ASSISTANT_REASONING, "new", false, ARRIVED), () -> 2,
                outputs::add, timerTasks::add);
            timerTasks.getFirst().run();

            assertEquals(List.of("first"), outputs.stream()
                .map(SessionPreviewAggregator.PreviewOutput::text).toList());
            assertEquals(1, aggregator.pendingSessions());
            aggregator.finish("session", outputs::add, false);
            assertEquals(List.of("first", "new"), outputs.stream()
                .map(SessionPreviewAggregator.PreviewOutput::text).toList());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private SessionPreviewAggregator.PreviewInput input(String runId, EventType type, String text, boolean reset,
        Instant timestamp) {
        return new SessionPreviewAggregator.PreviewInput("session", runId, type, text, reset, timestamp);
    }

    private SessionPreviewAggregator.PreviewInput inputFor(String sessionId, String runId, String text) {
        return new SessionPreviewAggregator.PreviewInput(sessionId, runId, EventType.ASSISTANT_PREVIEW, text, false,
            ARRIVED);
    }
}
