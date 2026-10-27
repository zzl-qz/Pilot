package com.pilot.core.session;

import com.pilot.core.event.ApprovalDecision;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (received.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void rejectsUnknownSession() {
        assertThrows(NoSuchSessionException.class, () -> sessions.sendMessage("ses_missing", "hi"));
        assertThrows(NoSuchSessionException.class, () -> sessions.events("ses_missing"));
    }
}
