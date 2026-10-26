package com.pilot.server.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.session.InterruptedRun;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptedRunRecoveryTest {

    @Test
    void marksSessionInterruptedWhenRunSurvivesRestart() throws Exception {
        var sessions = new SessionService(Clock.systemUTC());
        var session = sessions.createSession("重启收敛");
        var run = sessions.sendMessage(session.id(), "跑一个长任务");
        assertEquals(SessionStatus.RUNNING, sessions.find(session.id()).orElseThrow().status());

        recovery(sessions, List.of(new InterruptedRun(run.runId(), session.id())))
                .run(new DefaultApplicationArguments());

        // 会话不能停在 RUNNING，否则界面永远显示「正在处理」
        assertEquals(SessionStatus.INTERRUPTED, sessions.find(session.id()).orElseThrow().status());
        var last = lastEvent(sessions, session.id());
        var failed = assertInstanceOf(RunFailed.class, last.payload());
        assertEquals("INTERRUPTED", failed.code());
        assertTrue(failed.retryable(), "重启中断应允许继续");
        assertEquals(run.runId(), last.runId());
    }

    @Test
    void closesSessionEvenWhenRunRowIsMissing() throws Exception {
        var sessions = new SessionService(Clock.systemUTC());
        var session = sessions.createSession("事件丢失");
        sessions.sendMessage(session.id(), "任务");

        // runs 表里没有对应记录时也要收敛，避免留下僵死的 RUNNING
        recovery(sessions, List.of()).run(new DefaultApplicationArguments());

        assertEquals(SessionStatus.INTERRUPTED, sessions.find(session.id()).orElseThrow().status());
    }

    @Test
    void leavesTerminalSessionAlone() throws Exception {
        var sessions = new SessionService(Clock.systemUTC());
        var session = sessions.createSession("已完成");
        var run = sessions.sendMessage(session.id(), "任务");
        sessions.publish(session.id(), run.runId(), new RunCompleted("完成", List.of(), List.of()));
        var before = sessions.events(session.id()).size();

        recovery(sessions, List.of()).run(new DefaultApplicationArguments());

        assertEquals(SessionStatus.COMPLETED, sessions.find(session.id()).orElseThrow().status());
        assertEquals(before, sessions.events(session.id()).size(), "终态会话不应再补事件");
    }

    private static InterruptedRunRecovery recovery(SessionService sessions, List<InterruptedRun> interrupted) {
        RunRepository runs = new RunRepository() {
            @Override
            public void started(String runId, String sessionId, Instant at) {
            }

            @Override
            public void finished(String runId, String status, Instant at) {
            }

            @Override
            public List<InterruptedRun> markInterrupted(Instant at) {
                return interrupted;
            }
        };
        return new InterruptedRunRecovery(runs, sessions);
    }

    private static EventEnvelope lastEvent(SessionService sessions, String sessionId) {
        var events = sessions.events(sessionId);
        return events.get(events.size() - 1);
    }
}
