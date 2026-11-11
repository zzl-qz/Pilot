package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.AssistantDelta;

class EventStreamBoundsTest {
    private SessionService service(long sendMillis) {
        return new SessionService(Clock.systemUTC(), new InMemorySessionRepository(),
            new EventStreamLimits(4096, 4096, 8192, 4, 2, 32, 1000, sendMillis, 5000, 1024));
    }

    @Test
    void bytesAreBoundedBeforeItemLimitAndClosedQueuesReleaseTheirBudget() throws Exception {
        var service = service(5000);
        String id = service.createSession("字节预算").id();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);

        var subscription = service.subscribe(id, 0, event -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, closed::countDown);
        try {
            service.publish(id, "r", new AssistantDelta("a"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.publish(id, "r", new AssistantDelta("x".repeat(600)));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertEquals("queue_budget", subscription.closeReason());
            assertTrue(service.streamMetrics().peakQueuedBytes() <= 4096);
        } finally {
            release.countDown();
            subscription.close();
        }
        await(() -> service.streamMetrics().workers() == 0);
        assertEquals(0, service.streamMetrics().queuedBytes());
    }

    @Test
    void sendingDeadlineInterruptsStalledConsumerAndLimitsConcurrentConnections() throws Exception {
        var service = service(50);
        String id = service.createSession("超时").id();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);

        var subscription = service.subscribe(id, 0, event -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, closed::countDown);
        try (var second = service.subscribe(id, 0, event -> {
        })) {
            assertThrows(IllegalStateException.class, () -> service.subscribe(id, 0, event -> {
            }));
            service.publish(id, "r", new AssistantDelta("a"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertEquals("send_timeout", subscription.closeReason());
        } finally {
            subscription.close();
        }
        await(() -> service.streamMetrics().workers() == 0);
        assertEquals(0, service.streamMetrics().queuedBytes());
    }

    @Test
    void largeDurableEventUsesReferenceInBothReplayAndLiveQueue() throws Exception {
        var service = service(1000);
        String id = service.createSession("大事件引用").id();
        service.publish(id, "r", new AssistantDelta("x".repeat(20_000)));
        var references = new CopyOnWriteArrayList<Long>();
        try (var subscription = service.subscribe(id, 0, null, event -> fail("大事件不能直接交付"),
            reference -> references.add(reference.sequence()), () -> {
            })) {
            service.publish(id, "r", new AssistantDelta("y".repeat(20_000)));
            await(() -> references.size() == 2);
            assertEquals(List.of(1L, 2L), references);
            assertEquals(2, service.events(id).size());
            assertTrue(service.streamMetrics().peakQueuedBytes() <= 1024);
        }
    }

    @Test
    void concurrentReplayPagesAreBoundedAndWaitingWorkersAreReleased() throws Exception {
        var service = service(5000);
        String id = service.createSession("分页预算").id();
        service.publish(id, "r", new AssistantDelta("历史"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = service.subscribe(id, 0, event -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var second = service.subscribe(id, 0, event -> {
        });
        assertEquals(1, service.streamMetrics().peakReplayPages());
        second.close();
        first.close();
        release.countDown();
        await(() -> service.streamMetrics().workers() == 0);
        assertEquals(0, service.streamMetrics().replayPages());
        assertEquals(0, service.streamMetrics().queuedBytes());
    }

    @Test
    void externalRetentionRevisionClosesIdleStreamWithoutWaitingForNewEvents() throws Exception {
        var repository = new InMemorySessionRepository();
        var service = new SessionService(Clock.systemUTC(), repository);
        String id = service.createSession("保留策略失效").id();
        var replayed = new CountDownLatch(1);
        service.sendMessage(id, "开始");
        try (var sub = service.subscribe(id, 0, event -> replayed.countDown())) {
            assertTrue(replayed.await(2, TimeUnit.SECONDS));
            repository.advanceHistoryRevision(id);
            await(sub::isClosed);
            assertEquals("history_changed", sub.closeReason());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }
}
