package com.pilot.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceCoordinationEventBusTest {
    @Test
    void publishesInOrderWithMonotonicSequence(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus();
        bus.publish(draft(WorkspaceEventType.SESSION_STARTED, "s1", root));
        bus.publish(draft(WorkspaceEventType.RUN_STARTED, "s1", root));
        bus.publish(draft(WorkspaceEventType.RUN_FINISHED, "s1", root));

        var page = bus.replay(0, scope("p1", root), 10);

        assertEquals(List.of(1L, 2L, 3L), page.stream().map(WorkspaceCoordinationEvent::sequence).toList());
        assertEquals(3, bus.latestSequence());
        assertEquals(1, bus.earliestRetainedSequence());
    }

    @Test
    void subscriberReplaysHistoryThenFollowsLiveWithoutDuplicates(@TempDir Path root) throws Exception {
        var bus = new WorkspaceCoordinationEventBus();
        bus.publish(draft(WorkspaceEventType.SESSION_STARTED, "s1", root));
        bus.publish(draft(WorkspaceEventType.RUN_STARTED, "s1", root));
        List<WorkspaceCoordinationEvent> received = Collections.synchronizedList(new ArrayList<>());

        var subscription = bus.subscribe(scope("p1", root), 0, received::add);
        awaitUntil(() -> received.size() == 2);
        bus.publish(draft(WorkspaceEventType.SANDBOX_CHANGED, "s2", root));
        awaitUntil(() -> received.size() == 3);

        assertEquals(List.of(1L, 2L, 3L), received.stream().map(WorkspaceCoordinationEvent::sequence).toList());
        assertEquals(3, subscription.cursor());
        subscription.close();
    }

    @Test
    void reconnectFromCursorOnlyGetsLaterEvents(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus();
        for (int index = 0; index < 5; index++) {
            bus.publish(draft(WorkspaceEventType.RUN_STARTED, "s1", root));
        }

        var resumed = bus.replay(2, scope("p1", root), 10);

        assertEquals(List.of(3L, 4L, 5L), resumed.stream().map(WorkspaceCoordinationEvent::sequence).toList());
    }

    @Test
    void differentProjectsDoNotSeeEachOtherEvents(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus();
        bus.publish(new WorkspaceEventDraft(WorkspaceEventType.SANDBOX_CHANGED, "p1", "s1", root.toString(), "改动",
            Map.of()));
        bus.publish(new WorkspaceEventDraft(WorkspaceEventType.SANDBOX_CHANGED, "p2", "s2", root.toString(), "改动",
            Map.of()));

        var first = bus.replay(0, scope("p1", root), 10);
        var second = bus.replay(0, scope("p2", root), 10);
        var anonymous = bus.replay(0, ProjectCoordinationScope.ofDirectory(root, false), 10);

        assertEquals(List.of("s1"), first.stream().map(WorkspaceCoordinationEvent::sessionId).toList());
        assertEquals(List.of("s2"), second.stream().map(WorkspaceCoordinationEvent::sessionId).toList());
        assertEquals(2, anonymous.size());
    }

    @Test
    void onlyDirectoriesInsideTheScopeAreVisible(@TempDir Path root) {
        Path primary = root.resolve("primary");
        Path attached = root.resolve("attached");
        Path sibling = root.resolve("sibling");
        var bus = new WorkspaceCoordinationEventBus();
        bus.publish(draft(WorkspaceEventType.FILE_CLAIMED, "s1", primary));
        bus.publish(draft(WorkspaceEventType.FILE_CLAIMED, "s2", attached));
        bus.publish(draft(WorkspaceEventType.FILE_CLAIMED, "s3", sibling));

        var page = bus.replay(0, ProjectCoordinationScope.of("p1", List.of(primary, attached), false), 10);

        assertEquals(List.of("s1", "s2"), page.stream().map(WorkspaceCoordinationEvent::sessionId).toList());
    }

    @Test
    void projectLevelEventsOnlyReachTheSameProject(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus();
        bus.publish(new WorkspaceEventDraft(WorkspaceEventType.ARBITRATION_CREATED, "p1", "s1", null, "仲裁", Map.of()));

        assertTrue(bus.replay(0, scope("p1", root), 10).size() == 1);
        assertTrue(bus.replay(0, scope("p2", root), 10).isEmpty());
        assertTrue(bus.replay(0, ProjectCoordinationScope.ofDirectory(root, false), 10).isEmpty());
    }

    @Test
    void emptyScopeSeesNothing(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus();
        bus.publish(draft(WorkspaceEventType.RUN_STARTED, "s1", root));

        assertTrue(bus.replay(0, ProjectCoordinationScope.of(null, List.of(), false), 10).isEmpty());
    }

    @Test
    void slowSubscriberIsClosedWithQueueOverflow(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus(new WorkspaceEventLimits(100, 4L * 1024 * 1024, 8192, 4, 2, 10));
        CountDownLatch release = new CountDownLatch(1);
        var subscription = bus.subscribe(scope("p1", root), 0, event -> awaitLatch(release));

        bus.publish(draft(WorkspaceEventType.RUN_STARTED, "s1", root));
        for (int index = 0; index < 20; index++) {
            bus.publish(draft(WorkspaceEventType.SANDBOX_CHANGED, "s1", root));
        }
        awaitUntil(subscription::isClosed);
        release.countDown();

        assertEquals("queue_overflow", subscription.closeReason());
        assertEquals(0, bus.subscriptionCount());
    }

    @Test
    void closedSubscriptionStopsDelivery(@TempDir Path root) throws Exception {
        var bus = new WorkspaceCoordinationEventBus();
        List<WorkspaceCoordinationEvent> received = Collections.synchronizedList(new ArrayList<>());
        var subscription = bus.subscribe(scope("p1", root), 0, received::add);

        subscription.close();
        bus.publish(draft(WorkspaceEventType.RUN_STARTED, "s1", root));
        Thread.sleep(100);

        assertTrue(subscription.isClosed());
        assertEquals("closed", subscription.closeReason());
        assertTrue(received.isEmpty());
        assertEquals(0, bus.subscriptionCount());
    }

    @Test
    void subscriptionLimitRejectsExtraSubscribers(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus(new WorkspaceEventLimits(10, 1024 * 1024, 8192, 1, 4, 4));
        bus.subscribe(scope("p1", root), 0, event -> {
        });

        assertThrows(IllegalStateException.class, () -> bus.subscribe(scope("p1", root), 0, event -> {
        }));
    }

    @Test
    void oversizedEventsAreTruncatedInsteadOfRejected(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus(new WorkspaceEventLimits(10, 1024 * 1024, 256, 4, 4, 4));
        String huge = "x".repeat(50_000);

        var event = bus.publish(new WorkspaceEventDraft(WorkspaceEventType.FILE_CLAIMED, "p1", "s1", root.toString(),
            huge, Map.of("content", huge)));

        assertTrue(event.estimatedBytes() <= 256, () -> "事件仍超出上限：" + event.estimatedBytes());
        assertTrue(event.details().isEmpty(), "超大事件的细节必须被丢弃");
        assertTrue(event.subject().length() < huge.length());
        assertTrue(bus.replay(0, scope("p1", root), 4).getFirst().estimatedBytes() <= 256);
    }

    @Test
    void eventWithHugeDirectoryDegradesToProjectLevel(@TempDir Path root) {
        String hugeDirectory = root + "/" + "dir".repeat(200);
        var event = new WorkspaceCoordinationEvent(1, Instant.EPOCH, WorkspaceEventType.FILE_CLAIMED, "p1",
            "s1", hugeDirectory, "claim", Map.of());

        var compacted = event.compacted(200);

        assertTrue(compacted.estimatedBytes() <= 200);
        assertNull(compacted.directory());
        assertTrue(compacted.visibleTo(scope("p1", root)));
        assertFalse(compacted.visibleTo(scope("p2", root)));
    }

    @Test
    void historyStaysBoundedAndReportsEarliestRetained(@TempDir Path root) {
        var bus = new WorkspaceCoordinationEventBus(new WorkspaceEventLimits(2, 4L * 1024 * 1024, 8192, 4, 4, 4));
        for (int index = 0; index < 5; index++) {
            bus.publish(draft(WorkspaceEventType.RUN_STARTED, "s1", root));
        }

        assertEquals(5, bus.latestSequence());
        assertEquals(4, bus.earliestRetainedSequence());
        assertEquals(List.of(4L, 5L), bus.replay(0, scope("p1", root), 10).stream()
            .map(WorkspaceCoordinationEvent::sequence).toList());
        assertEquals(3, bus.metrics().droppedEvents());
    }

    @Test
    void concurrentPublishersKeepStrictOrderWithoutLoss(@TempDir Path root) throws Exception {
        var bus = new WorkspaceCoordinationEventBus();
        List<WorkspaceCoordinationEvent> received = Collections.synchronizedList(new ArrayList<>());
        var subscription = bus.subscribe(scope("p1", root), 0, received::add);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            for (String sessionId : List.of("s1", "s2")) {
                pool.submit(() -> {
                    awaitLatch(start);
                    for (int index = 0; index < 50; index++) {
                        bus.publish(draft(WorkspaceEventType.SANDBOX_CHANGED, sessionId, root));
                    }
                    return null;
                });
            }
            start.countDown();
            awaitUntil(() -> received.size() == 100);
            awaitUntil(() -> subscription.deliveredCount() == 100);
        } finally {
            pool.shutdownNow();
            subscription.close();
        }

        List<Long> sequences = received.stream().map(WorkspaceCoordinationEvent::sequence).toList();
        for (int index = 1; index < sequences.size(); index++) {
            assertTrue(sequences.get(index) > sequences.get(index - 1), "事件顺序必须严格递增：" + sequences);
        }
        assertEquals(100, sequences.size());
    }

    @Test
    void limitsRejectMeaninglessBounds() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEventLimits(0, 1024, 256, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEventLimits(1, 1024, 12, 1, 1, 1));
    }

    private static WorkspaceEventDraft draft(WorkspaceEventType type, String sessionId, Path directory) {
        return WorkspaceEventDraft.of(type, sessionId, directory.toString(), type.wireName());
    }

    private static ProjectCoordinationScope scope(String projectId, Path directory) {
        return ProjectCoordinationScope.of(projectId, List.of(directory), false);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断", interrupted);
            }
        }
        throw new AssertionError("条件在 5 秒内没有成立");
    }
}
