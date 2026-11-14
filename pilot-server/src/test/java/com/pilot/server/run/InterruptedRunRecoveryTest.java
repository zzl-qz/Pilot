package com.pilot.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.session.InterruptedRun;
import com.pilot.core.session.Run;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;

class InterruptedRunRecoveryTest {

    @Test
    void marksSessionInterruptedWhenRunSurvivesRestart() throws Exception {
        SessionService sessions = new SessionService(Clock.systemUTC());
        Session session = sessions.createSession("重启收敛");
        Run run = sessions.sendMessage(session.id(), "跑一个长任务");
        assertEquals(SessionStatus.RUNNING, sessions.find(session.id()).orElseThrow().status());

        recovery(sessions, List.of(new InterruptedRun(run.runId(), session.id())))
            .run(new DefaultApplicationArguments());

        // 会话不能停在 RUNNING，否则界面永远显示「正在处理」
        assertEquals(SessionStatus.INTERRUPTED, sessions.find(session.id()).orElseThrow().status());
        EventEnvelope last = lastEvent(sessions, session.id());
        RunFailed failed = assertInstanceOf(RunFailed.class, last.payload());
        assertEquals("INTERRUPTED", failed.code());
        assertTrue(failed.retryable(), "重启中断应允许继续");
        assertEquals(run.runId(), last.runId());
    }

    @Test
    void closesSessionEvenWhenRunRowIsMissing() throws Exception {
        SessionService sessions = new SessionService(Clock.systemUTC());
        Session session = sessions.createSession("事件丢失");
        sessions.sendMessage(session.id(), "任务");

        // runs 表里没有对应记录时也要收敛，避免留下僵死的 RUNNING
        recovery(sessions, List.of()).run(new DefaultApplicationArguments());

        assertEquals(SessionStatus.INTERRUPTED, sessions.find(session.id()).orElseThrow().status());
    }

    @Test
    void leavesTerminalSessionAlone() throws Exception {
        SessionService sessions = new SessionService(Clock.systemUTC());
        Session session = sessions.createSession("已完成");
        Run run = sessions.sendMessage(session.id(), "任务");
        sessions.publish(session.id(), run.runId(), new RunCompleted("完成", List.of(), List.of()));
        int before = sessions.events(session.id()).size();

        recovery(sessions, List.of()).run(new DefaultApplicationArguments());

        assertEquals(SessionStatus.COMPLETED, sessions.find(session.id()).orElseThrow().status());
        assertEquals(before, sessions.events(session.id()).size(), "终态会话不应再补事件");
    }

    @Test
    void leavesBlankSessionAlone() throws Exception {
        SessionService sessions = new SessionService(Clock.systemUTC());
        // 刚建好、还没发过任何消息的对话：重启不该让它变成「运行已中断」
        Session session = sessions.createSession("还没开始", null, null, true);

        recovery(sessions, List.of(new InterruptedRun("run_blank", session.id())))
            .run(new DefaultApplicationArguments());

        assertEquals(SessionStatus.CREATED, sessions.find(session.id()).orElseThrow().status());
        assertEquals(0, sessions.events(session.id()).size(), "空白对话不应被补中断事件");
    }

    @Test
    void leavesIdleSessionAloneEvenWhenRunRowSurvives() throws Exception {
        SessionService sessions = new SessionService(Clock.systemUTC());
        Session session = sessions.createSession("已完成一轮");
        Run run = sessions.sendMessage(session.id(), "任务");
        sessions.publish(session.id(), run.runId(), new RunCompleted("完成", List.of(), List.of()));
        int before = sessions.events(session.id()).size();

        // runs 表里残留一条 RUNNING（历史脏数据）也不该给已结束的会话再造中断卡片
        recovery(sessions, List.of(new InterruptedRun("run_stale", session.id())))
            .run(new DefaultApplicationArguments());

        assertEquals(SessionStatus.COMPLETED, sessions.find(session.id()).orElseThrow().status());
        assertEquals(before, sessions.events(session.id()).size());
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
        List<EventEnvelope> events = sessions.events(sessionId);
        return events.get(events.size() - 1);
    }
}
