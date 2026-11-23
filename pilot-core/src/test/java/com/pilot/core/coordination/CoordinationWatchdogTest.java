package com.pilot.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionStatus;

class CoordinationWatchdogTest {

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant start) {
            this.current = start;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class Env {
        final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
        final InMemorySessionRepository sessions = new InMemorySessionRepository();
        final InMemoryProjectRepository projects = new InMemoryProjectRepository();
        final List<WorkspaceEventDraft> published = new ArrayList<>();
        final SessionCoordinationService service;

        Env(CoordinationSettings settings) {
            WorkspaceEventPublisher publisher = draft -> {
                published.add(draft);
                return new WorkspaceCoordinationEvent(published.size(), clock.instant(), draft.type(),
                    draft.projectId(), draft.sessionId(), draft.directory(), draft.subject(), draft.details());
            };
            this.service = new SessionCoordinationService(sessions, new CoordinationScopeResolver(projects, false),
                publisher, new CoordinationSnapshotStore.InMemory(), settings.withClock(clock));
        }

        void project(String id, Path root) {
            projects.create(id, "项目 " + id, List.of(root.toString()), clock.instant());
        }

        Session session(String id, String title, Path workspace, String projectId) {
            return sessions.create(id, title, workspace.toString(), clock.instant(), projectId, false);
        }
    }

    @Test
    void releasesClaimsOwnedByFailedOrInterruptedSessionsImmediately(@TempDir Path root) {
        Env env = new Env(CoordinationSettings.defaults());
        env.project("p1", root);
        Session alpha = env.session("ses_alpha", "会话 A", root, "p1");
        Session beta = env.session("ses_beta", "会话 B", root, "p1");

        env.service.claim(alpha.id(), root.resolve("a.java").toString(), CoordinationClaimKind.FILE, null);
        env.service.claim(beta.id(), root.resolve("b.java").toString(), CoordinationClaimKind.FILE, null);

        env.sessions.updateStatus(alpha.id(), SessionStatus.FAILED);
        env.sessions.updateStatus(beta.id(), SessionStatus.PAUSED);

        SessionCoordinationService.WatchdogReport report = env.service.patrol();

        assertEquals(1, report.sweptCount(), "失败会话的幽灵占用应立刻释放，暂停会话继续保留");
        CoordinationSnapshot snapshot = env.service.snapshot(beta.id());
        assertEquals(1, snapshot.claims().size());
        assertEquals(beta.id(), snapshot.claims().getFirst().sessionId());
        assertTrue(env.published.stream().anyMatch(e -> e.type() == WorkspaceEventType.FILE_RELEASED
            && SessionCoordinationService.OWNER_INACTIVE.equals(e.details().get("reason"))));
    }

    @Test
    void expiresPendingCoordinationRequestsAfterRequestTtl(@TempDir Path root) {
        Env env = new Env(CoordinationSettings.defaults().withRequestTtl(Duration.ofMinutes(15)));
        env.project("p1", root);
        Session alpha = env.session("ses_alpha", "会话 A", root, "p1");
        Session beta = env.session("ses_beta", "会话 B", root, "p1");

        CoordinationRequest req = env.service.request(alpha.id(), beta.id(), "请帮忙确认接口", "T-1");
        assertEquals(CoordinationRequestStatus.PENDING, req.status());

        env.clock.advance(Duration.ofMinutes(16));
        SessionCoordinationService.WatchdogReport report = env.service.patrol();

        assertEquals(1, report.sweptCount());
        CoordinationRequest updated = env.service.snapshot(alpha.id()).requests().getFirst();
        assertEquals(CoordinationRequestStatus.EXPIRED, updated.status());
        assertTrue(env.published.stream().anyMatch(
            e -> e.type() == WorkspaceEventType.COORDINATION_UPDATED && "EXPIRED".equals(e.details().get("status"))));
    }

    @Test
    void detectsWaitForDeadlockCyclesAndDeduplicatesAlerts(@TempDir Path root) {
        Env env = new Env(CoordinationSettings.defaults());
        env.project("p1", root);
        Session alpha = env.session("ses_alpha", "会话 A", root, "p1");
        Session beta = env.session("ses_beta", "会话 B", root, "p1");

        env.service.request(alpha.id(), beta.id(), "等待 B 输出契约", "T-A");
        CoordinationRequest reverse = env.service.request(beta.id(), alpha.id(), "等待 A 先改前端", "T-B");

        SessionCoordinationService.WatchdogReport first = env.service.patrol();
        assertEquals(1, first.deadlockCount());
        long alertEventsAfterFirst =
            env.published.stream().filter(e -> e.type() == WorkspaceEventType.COORDINATION_ALERT).count();
        assertEquals(1, alertEventsAfterFirst);

        // 连续巡检不能重复刷屏同一死锁告警
        SessionCoordinationService.WatchdogReport second = env.service.patrol();
        assertEquals(1, second.deadlockCount());
        assertEquals(1, env.published.stream().filter(e -> e.type() == WorkspaceEventType.COORDINATION_ALERT).count());

        CoordinationSnapshot snapshot = env.service.snapshot(alpha.id());
        assertEquals(1, snapshot.alerts().size());
        assertEquals(CoordinationAlert.DEADLOCK, snapshot.alerts().getFirst().kind());

        // 撤销其中一条请求后死锁解除
        env.service.withdrawRequest(beta.id(), reverse.id());
        assertEquals(0, env.service.patrol().deadlockCount());
        assertTrue(env.service.snapshot(alpha.id()).alerts().isEmpty());
    }

    @Test
    void marksLongSilentRunningSessionAsStalledAndAlertsWaiter(@TempDir Path root) {
        Env env = new Env(CoordinationSettings.defaults().withStallTimeout(Duration.ofMinutes(3)));
        env.project("p1", root);
        Session alpha = env.session("ses_alpha", "会话 A", root, "p1");
        Session beta = env.session("ses_beta", "会话 B", root, "p1");
        env.sessions.updateStatus(beta.id(), SessionStatus.RUNNING);

        env.service.request(alpha.id(), beta.id(), "等待 B 完成", "T-2");
        assertFalse(env.service.snapshot(alpha.id()).sessions().stream().filter(s -> s.sessionId().equals(beta.id()))
            .findFirst().orElseThrow().stalled());

        env.clock.advance(Duration.ofMinutes(4));
        SessionCoordinationService.WatchdogReport report = env.service.patrol();
        assertEquals(1, report.stalledSessionCount());

        CoordinationSnapshot snapshot = env.service.snapshot(alpha.id());
        CoordinationSessionView betaView =
            snapshot.sessions().stream().filter(s -> s.sessionId().equals(beta.id())).findFirst().orElseThrow();
        assertTrue(betaView.stalled(), "RUNNING 超过静默阈值应标记为疑似停滞");
        assertTrue(snapshot.alerts().stream().anyMatch(a -> CoordinationAlert.STALLED_DEPENDENCY.equals(a.kind())));
    }
}
