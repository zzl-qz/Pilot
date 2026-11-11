package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.ApprovalDecision;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;

class SessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final SessionService sessions = new SessionService(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createThenSendMessageRecordsRunStarted() {
        Session session = sessions.createSession("演示会话");

        assertEquals(SessionStatus.CREATED, session.status());
        assertTrue(sessions.events(session.id()).isEmpty());

        Run run = sessions.sendMessage(session.id(), "修复边界条件");

        List<EventEnvelope> events = sessions.events(session.id());
        assertEquals(1, events.size());
        EventEnvelope envelope = events.get(0);
        assertEquals(1, envelope.sequence());
        assertEquals(NOW, envelope.timestamp());
        assertEquals("run.started", envelope.type());
        assertEquals(session.id(), envelope.sessionId());
        assertEquals(run.runId(), envelope.runId());
        assertEquals(SessionStatus.RUNNING, sessions.find(session.id()).orElseThrow().status());
    }

    @Test
    void sequencesIncreaseWithinSession() {
        Session session = sessions.createSession("演示会话");
        Run first = sessions.sendMessage(session.id(), "第一条");
        sessions.publish(session.id(), first.runId(), new AssistantDelta("a"));
        Run second = sessions.sendMessage(session.id(), "第二条");

        List<EventEnvelope> events = sessions.events(session.id());
        assertEquals(List.of(1L, 2L, 3L), events.stream().map(EventEnvelope::sequence).toList());
        assertEquals(second.runId(), events.get(2).runId());
        assertEquals(1, sessions.eventsAfter(session.id(), 2).size());
    }

    @Test
    void tracksSessionStatusFromEvents() {
        Session session = sessions.createSession("演示会话");
        Run run = sessions.sendMessage(session.id(), "改文件");

        sessions.publish(session.id(), run.runId(), new ApprovalRequired("call_1", "write_file", "+1 -1"));
        assertEquals(SessionStatus.WAITING_APPROVAL, sessions.find(session.id()).orElseThrow().status());

        sessions.publish(session.id(), run.runId(), new ApprovalResolved("call_1", ApprovalDecision.APPROVED));
        assertEquals(SessionStatus.RUNNING, sessions.find(session.id()).orElseThrow().status());

        sessions.publish(session.id(), run.runId(), new RunCompleted("完成", List.of(), List.of()));
        assertEquals(SessionStatus.COMPLETED, sessions.find(session.id()).orElseThrow().status());
    }

    @Test
    void cancellationHasItsOwnEventAndTerminalSessionStatus() {
        Session session = sessions.createSession("取消测试");
        Run run = sessions.sendMessage(session.id(), "开始任务");

        EventEnvelope envelope = sessions.publish(session.id(), run.runId(), new RunCancelled("用户取消"));

        assertEquals("run.cancelled", envelope.type());
        assertEquals(SessionStatus.CANCELLED, sessions.find(session.id()).orElseThrow().status());
        assertTrue(sessions.find(session.id()).orElseThrow().status().terminal());
    }

    @Test
    void userPauseHasItsOwnTerminalStatusAndCanResume() {
        Session session = sessions.createSession("暂停测试");
        Run run = sessions.sendMessage(session.id(), "开始任务");

        sessions.publish(session.id(), run.runId(), new RunFailed("PAUSED", "用户暂停", true));

        assertEquals(SessionStatus.PAUSED, sessions.find(session.id()).orElseThrow().status());
        assertTrue(sessions.find(session.id()).orElseThrow().status().terminal());

        sessions.sendMessage(session.id(), "继续原任务");
        assertEquals(SessionStatus.RUNNING, sessions.find(session.id()).orElseThrow().status());
    }

    @Test
    void stepLimitIsNotAnExplicitUserPause() {
        Session session = sessions.createSession("步数测试");
        Run run = sessions.sendMessage(session.id(), "开始任务");

        sessions.publish(session.id(), run.runId(), new RunFailed("MAX_STEPS", "达到步数上限", true));

        assertEquals(SessionStatus.FAILED, sessions.find(session.id()).orElseThrow().status());
    }

    @Test
    void subscriberReceivesHistoryThenLiveEvents() throws InterruptedException {
        Session session = sessions.createSession("演示会话");
        Run run = sessions.sendMessage(session.id(), "你好");
        List<EventEnvelope> received = new CopyOnWriteArrayList<>();

        EventSubscription subscription = sessions.subscribe(session.id(), 0, received::add);
        sessions.publish(session.id(), run.runId(), new AssistantDelta("世界"));

        // 投递是异步的（慢客户端不能拖住发布线程），这里等它追上
        awaitSize(received, 2);
        assertEquals(List.of(1L, 2L), received.stream().map(EventEnvelope::sequence).toList());

        subscription.close();
        sessions.publish(session.id(), run.runId(), new AssistantDelta("离线"));

        assertEquals(2, received.size());
    }

    private static void awaitSize(List<EventEnvelope> received, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (received.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void rejectsUnknownSession() {
        assertThrows(NoSuchSessionException.class, () -> sessions.sendMessage("ses_missing", "hi"));
        assertThrows(NoSuchSessionException.class, () -> sessions.events("ses_missing"));
    }

    @Test
    void failedReplayNeverRegistersClosedSubscription() throws Exception {
        Session session = sessions.createSession("回放失败");
        sessions.sendMessage(session.id(), "第一条");
        sessions.sendMessage(session.id(), "第二条");
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch notified = new CountDownLatch(1);
        EventSubscription subscription = sessions.subscribe(session.id(), 0, event -> {
            sent.incrementAndGet();
            throw new IllegalStateException("断线");
        }, () -> { closed.incrementAndGet(); notified.countDown(); });
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        subscription.close();
        assertEquals(0, sessions.subscriberCount());
        assertEquals(1, sent.get());
        assertEquals(1, closed.get());
    }

    @Test
    void failedHistoryQueryReleasesSubscription() throws Exception {
        SessionRepository backing = new InMemorySessionRepository();
        SessionRepository failing = (SessionRepository) java.lang.reflect.Proxy.newProxyInstance(
            SessionRepository.class.getClassLoader(), new Class<?>[] {SessionRepository.class},
            (proxy, method, args) -> {
                if (method.getName().equals("replayPage")) throw new IllegalStateException("查询失败");
                return method.invoke(backing, args);
            });
        SessionService service = new SessionService(Clock.systemUTC(), failing);
        Session session = service.createSession("查询失败");
        service.sendMessage(session.id(), "有历史可回放");
        CountDownLatch closed = new CountDownLatch(1);
        service.subscribe(session.id(), 0, event -> {}, closed::countDown);
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        assertEquals(0, service.subscriberCount());
    }

    @Test
    void blockingCloseCallbackDoesNotHoldSessionLock() throws Exception {
        Session session = sessions.createSession("关闭回调");
        Run run = sessions.sendMessage(session.id(), "第一条");
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            sessions.subscribe(session.id(), 0, event -> { throw new IllegalStateException("断线"); }, () -> {
                closing.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> sessions.publish(session.id(), run.runId(), new AssistantDelta("继续")));
            assertEquals(0, sessions.subscriberCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    void deletionClosesAllSubscribersAndRepeatedCloseIsSafe() {
        Session session = sessions.createSession("删除");
        EventSubscription first = sessions.subscribe(session.id(), 0, event -> {});
        EventSubscription second = sessions.subscribe(session.id(), 0, event -> {});
        assertEquals(2, sessions.subscriberCount());
        assertTrue(sessions.delete(session.id()));
        first.close();
        second.close();
        assertEquals(0, sessions.subscriberCount());
        assertThrows(NoSuchSessionException.class, () -> sessions.subscribe(session.id(), 0, event -> {}));
        assertEquals(0, sessions.subscriberCount());
    }

    @Test
    void historyEditInvalidatesAllSubscribersAndRejectsOldRevision() throws Exception {
        String id = sessions.createSession("历史版本").id();
        sessions.sendMessage(id, "旧历史");
        CountDownLatch closed = new CountDownLatch(2);
        sessions.subscribe(id, 0, 0L, event -> {}, closed::countDown);
        sessions.subscribe(id, 0, 0L, event -> {}, closed::countDown);
        sessions.editHistory(id, 1, 0L, () -> {});
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        assertEquals(0, sessions.subscriberCount());
        assertEquals(1, sessions.historyRevision(id));
        assertThrows(SessionService.HistoryChangedException.class,
            () -> sessions.subscribe(id, 0, 0L, event -> {}, () -> {}));
        assertEquals(0, sessions.subscriberCount());
        sessions.subscribe(id, 0, 1L, event -> {}, () -> {}).close();
    }

    @Test
    void slowReplayDoesNotBlockPublisherOrAnotherSubscriber() throws Exception {
        String id = sessions.createSession("慢回放").id();
        Run run = sessions.sendMessage(id, "第一条");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<EventEnvelope> slow = new CopyOnWriteArrayList<>(), fast = new CopyOnWriteArrayList<>();
        try (var first = sessions.subscribe(id, 0, event -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            slow.add(event);
        })) {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> sessions.publish(id, run.runId(), new AssistantDelta("第二条")));
            try (var second = sessions.subscribe(id, 0, fast::add)) {
                awaitSize(fast, 2);
                assertEquals(List.of(1L, 2L), fast.stream().map(EventEnvelope::sequence).toList());
                release.countDown();
                awaitSize(slow, 2);
                assertEquals(List.of(1L, 2L), slow.stream().map(EventEnvelope::sequence).toList());
            }
        } finally { release.countDown(); }
    }

    @Test
    void pagedReplayUsesCapturedHighWaterAndJoinsConcurrentLiveEventsExactlyOnce() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger pages = new AtomicInteger();
        var repository = new InMemorySessionRepository() {
            @Override public List<EventEnvelope> eventsAfter(String id, long after) {
                throw new AssertionError("不能全量读取回放");
            }
            @Override public List<EventEnvelope> replayPage(String id, long after, long through, int limit) {
                assertEquals(800, through);
                if (pages.incrementAndGet() == 1) {
                    reading.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return super.replayPage(id, after, through, limit);
            }
        };
        var service = new SessionService(Clock.systemUTC(), repository);
        String id = service.createSession("分页").id();
        for (int i = 0; i < 800; i++) service.publish(id, "run", new AssistantDelta("字"));
        List<EventEnvelope> received = new CopyOnWriteArrayList<>();
        try (var subscription = service.subscribe(id, 0, received::add)) {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) service.publish(id, "run", new AssistantDelta("新"));
            release.countDown(); awaitSize(received, 900);
            assertEquals(java.util.stream.LongStream.rangeClosed(1, 900).boxed().toList(),
                received.stream().map(EventEnvelope::sequence).toList());
            assertEquals(4, pages.get());
        } finally { release.countDown(); }
    }
}
