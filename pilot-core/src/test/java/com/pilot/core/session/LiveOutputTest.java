package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCancelled;

class LiveOutputTest {
    private SessionService service(int bytes, int sessions) {
        return new SessionService(Clock.systemUTC(), new InMemorySessionRepository(), EventStreamLimits.defaults(),
            new PreviewLimits(10000, 8, bytes, bytes * 2L, sessions));
    }

    private LiveOutput snapshot(SessionService service, String id) throws Exception {
        var future = new CompletableFuture<LiveOutput>();
        try (var sub = service.subscribe(id, service.latestSequence(id), 0L, e -> {
        }, null, future::complete, () -> {
        })) {
            return future.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void reconnectCapturesPendingTextAndContinuesAfterSnapshotBoundary() throws Exception {
        var service = service(4096, 8);
        var id = service.createSession("恢复").id();
        var run = service.sendMessage(id, "开始").runId();
        service.preview(id, run, new AssistantReasoning("思考"));
        service.preview(id, run, new AssistantPreview("头"));
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<Object>();
        try (var sub = service.subscribe(id, 0, 0L, e -> {
            if (e.sequence() == 1 && e.type().equals("run.started")) {
                reached.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
            received.add(e);
        }, null, received::add, () -> {
        })) {
            assertTrue(reached.await(2, TimeUnit.SECONDS));
            service.preview(id, run, new AssistantPreview("尾"));
            service.publish(id, run, new AssistantDelta("头尾"));
            release.countDown();
            long end = System.nanoTime() + 3_000_000_000L;
            while (received.size() < 4 && System.nanoTime() < end) {
                Thread.sleep(5);
            }
            assertEquals(4, received.size());
            var state = (LiveOutput)received.get(1);
            var live = (EventEnvelope)received.get(2);
            assertEquals("头", state.text());
            assertEquals("思考", state.reasoning());
            assertTrue(state.complete());
            assertEquals(state.previewId(), live.previewId());
            assertTrue(live.previewOffset() > state.previewOffset());
            assertEquals("尾", ((AssistantPreview)live.payload()).text());
            assertEquals("assistant.delta", ((EventEnvelope)received.get(3)).type());
            assertEquals("", snapshot(service, id).text());
        } finally {
            release.countDown();
            service.delete(id);
        }
    }

    @Test
    void retryLimitsTerminalAndStaleRunCallbacksDoNotMix() throws Exception {
        var service = service(24, 2);
        var id = service.createSession("边界").id();
        var run = service.sendMessage(id, "开始").runId();
        service.preview(id, run, new AssistantPreview("abcdefghijk"));
        var bounded = snapshot(service, id);
        assertFalse(bounded.complete());
        assertEquals("abcd", bounded.text());
        assertTrue(service.streamMetrics().livePreviewBytes() <= 24);
        service.preview(id, run, AssistantPreview.discarded());
        service.preview(id, run, new AssistantPreview("新"));
        assertEquals("新", snapshot(service, id).text());
        assertTrue(snapshot(service, id).complete());
        service.publish(id, run, new RunCancelled("结束"));
        service.preview(id, run, new AssistantPreview("迟到"));
        assertEquals("", snapshot(service, id).text());
        assertEquals(0, service.streamMetrics().livePreviewBytes());
        var next = service.sendMessage(id, "继续").runId();
        service.preview(id, run, new AssistantPreview("旧回调"));
        service.preview(id, next, new AssistantPreview("新"));
        assertEquals("新", snapshot(service, id).text());
        service.dropEventsFrom(id, 1);
        assertEquals(0, service.streamMetrics().livePreviewSessions());
        service.delete(id);
    }

    @Test
    void staleCallbacksCannotReplaceCurrentPendingOutput() throws Exception {
        var service = service(4096, 8);
        var id = service.createSession("迟到回调").id();
        var old = service.sendMessage(id, "旧任务").runId();
        var current = service.sendMessage(id, "新任务").runId();
        var events = new CopyOnWriteArrayList<EventEnvelope>();
        try (var sub = service.subscribe(id, service.latestSequence(id), events::add)) {
            service.preview(id, current, new AssistantPreview("新"));
            service.preview(id, old, new AssistantPreview("旧"));
            service.preview(id, old, AssistantPreview.discarded());
            service.preview(id, old, new AssistantReasoning("旧推理"));
            service.preview(id, current, new AssistantPreview("文"));
            service.publish(id, current, new AssistantDelta("新文"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (events.stream().noneMatch(e -> e.type().equals("assistant.delta")) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(2, events.size());
            assertTrue(events.stream().allMatch(e -> e.runId().equals(current)));
            assertEquals("新文", ((AssistantPreview)events.getFirst().payload()).text());
        } finally {
            service.delete(id);
        }
    }

    @Test
    void globalCacheExhaustionIsExplicitAndDeletionReleasesIt() throws Exception {
        var service = service(24, 1);
        var a = service.createSession("A").id();
        var b = service.createSession("B").id();
        var ra = service.sendMessage(a, "开始").runId();
        var rb = service.sendMessage(b, "开始").runId();
        service.preview(a, ra, new AssistantPreview("abcd"));
        service.preview(b, rb, new AssistantPreview("efgh"));
        assertFalse(snapshot(service, b).complete());
        assertEquals(1, service.streamMetrics().livePreviewSessions());
        service.delete(a);
        assertEquals(0, service.streamMetrics().livePreviewBytes());
        service.delete(b);
    }
}
