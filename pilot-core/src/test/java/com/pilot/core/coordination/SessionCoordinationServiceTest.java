package com.pilot.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemorySessionRepository;

/**
 * 协调协议的并发与权限边界：同一位置只有一个占用方，接受/拒绝/撤掉只由该做的会话执行，
 * 过期与崩溃会话会自动让位，不同项目互相看不见。
 */
class SessionCoordinationServiceTest {

    private static final String PROJECT = "p1";

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }

    /** 每个用例一套独立状态，避免用例之间共享可变对象。 */
    private static final class Env {
        private final InMemorySessionRepository sessions = new InMemorySessionRepository();
        private final InMemoryProjectRepository projects = new InMemoryProjectRepository();
        private final CoordinationSnapshotStore store = new CoordinationSnapshotStore.InMemory();
        private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        private final Set<String> online = new HashSet<>();
        private final List<WorkspaceEventDraft> drafts = new ArrayList<>();
        private final CoordinationScopeResolver resolver = new CoordinationScopeResolver(projects, false);
        private final WorkspaceEventPublisher publisher = draft -> {
            drafts.add(draft);
            return new WorkspaceCoordinationEvent(drafts.size(), clock.instant(), draft.type(), draft.projectId(),
                draft.sessionId(), draft.directory(), draft.subject(), draft.details());
        };
        private final SessionCoordinationService service = new SessionCoordinationService(sessions, resolver,
            publisher, store,
            new CoordinationSettings(online::contains, clock, Duration.ofMinutes(10), Duration.ofHours(2)));

        SessionCoordinationService reopen() {
            return new SessionCoordinationService(sessions, resolver, publisher, store,
                new CoordinationSettings(online::contains, clock, Duration.ofMinutes(10), Duration.ofHours(2)));
        }

        void project(String id, List<Path> folders) {
            projects.create(id, "项目 " + id, folders.stream().map(Path::toString).toList(), clock.instant());
        }

        void session(String id, Path workspace, String projectId) {
            sessions.create(id, "会话 " + id, workspace.toString(), clock.instant(), projectId, false);
        }

        String tryClaim(String sessionId, String path) {
            try {
                service.claim(sessionId, path, CoordinationClaimKind.FILE, null);
                return "ok";
            } catch (CoordinationRefusedException refused) {
                return refused.code();
            }
        }
    }

    @Test
    void onlyOneSessionWinsTheSameFile(@TempDir Path root) throws Exception {
        Env env = new Env();
        Path shared = Files.createDirectories(root.resolve("shared"));
        Path file = shared.resolve("App.java");
        env.project(PROJECT, List.of(shared));
        env.session("s1", shared, PROJECT);
        env.session("s2", shared, PROJECT);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> outcomes = List.of(pool.submit(() -> {
                start.await();
                return env.tryClaim("s1", file.toString());
            }), pool.submit(() -> {
                start.await();
                return env.tryClaim("s2", file.toString());
            }));
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get());
            }
            assertEquals(1, results.stream().filter("ok"::equals).count(), "必须只有一个占用成功：" + results);
            assertEquals(1, results.stream().filter(CoordinationRefusedException.CLAIMED::equals).count(),
                "另一个必须收到占用冲突：" + results);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parentDirectoryClaimBlocksChildPathAndReportsOwner(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path shared = Files.createDirectories(root.resolve("shared"));
        env.project(PROJECT, List.of(shared));
        env.session("s1", shared, PROJECT);
        env.session("s2", shared, PROJECT);

        env.service.claim("s1", shared.toString(), CoordinationClaimKind.DIRECTORY, null);

        CoordinationRefusedException blocked = assertThrows(CoordinationRefusedException.class,
            () -> env.service.claim("s2", shared.resolve("sub/File.java").toString(), CoordinationClaimKind.FILE,
                null));
        assertEquals(CoordinationRefusedException.CLAIMED, blocked.code());
        assertEquals("s1", blocked.ownerSessionId());
        assertNotNull(blocked.resource());

        CoordinationRefusedException precheck = assertThrows(CoordinationRefusedException.class,
            () -> env.service.requireUnclaimed("s2", shared.resolve("sub/File.java").toString()));
        assertEquals(CoordinationRefusedException.CLAIMED, precheck.code());
        assertTrue(precheck.getMessage().contains("s1"));
        assertTrue(env.service.requireUnclaimed("s1", shared.resolve("sub/File.java").toString()) == null);
    }

    @Test
    void releasedLocationCanBeClaimedAgain(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path shared = Files.createDirectories(root.resolve("shared"));
        env.project(PROJECT, List.of(shared));
        env.session("s1", shared, PROJECT);
        env.session("s2", shared, PROJECT);

        CoordinationClaim claim = env.service.claim("s1", shared.resolve("a.txt").toString(),
            CoordinationClaimKind.FILE, null);
        assertEquals(CoordinationRefusedException.DENIED,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.release("s2", claim.id())).code());
        CoordinationClaim released = env.service.release("s1", claim.id());
        assertEquals(claim.id(), released.id());
        assertEquals(CoordinationRefusedException.NOT_FOUND,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.release("s1", claim.id())).code());
        env.service.claim("s2", shared.resolve("a.txt").toString(), CoordinationClaimKind.FILE, null);

        List<WorkspaceEventType> types = env.drafts.stream().map(WorkspaceEventDraft::type).toList();
        assertEquals(List.of(WorkspaceEventType.FILE_CLAIMED, WorkspaceEventType.FILE_RELEASED,
            WorkspaceEventType.FILE_CLAIMED), types);
    }

    @Test
    void expiredLeaseAndDeletedSessionReleaseClaims(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path shared = Files.createDirectories(root.resolve("shared"));
        env.project(PROJECT, List.of(shared));
        env.session("s1", shared, PROJECT);
        env.session("s2", shared, PROJECT);

        env.service.claim("s1", shared.resolve("lease.txt").toString(), CoordinationClaimKind.FILE, null);
        env.clock.advance(Duration.ofMinutes(11));
        CoordinationSnapshot afterExpiry = env.service.snapshot("s2");
        assertTrue(afterExpiry.claims().isEmpty(), "租约到期后不再占位");
        assertTrue(env.drafts.stream().anyMatch(draft -> draft.type() == WorkspaceEventType.FILE_RELEASED
            && "lease-expired".equals(draft.details().get("reason"))));

        env.service.claim("s2", shared.resolve("crash.txt").toString(), CoordinationClaimKind.FILE, null);
        env.sessions.delete("s2");
        assertTrue(env.service.snapshot("s1").claims().isEmpty(), "会话消失后占用要被清掉");
        assertTrue(env.drafts.stream().anyMatch(draft -> draft.type() == WorkspaceEventType.FILE_RELEASED
            && "session-gone".equals(draft.details().get("reason"))));
        env.service.claim("s1", shared.resolve("crash.txt").toString(), CoordinationClaimKind.FILE, null);
    }

    @Test
    void requestIsIdempotentAndOnlyTargetMayDecide(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path shared = Files.createDirectories(root.resolve("shared"));
        env.project(PROJECT, List.of(shared));
        env.session("s1", shared, PROJECT);
        env.session("s2", shared, PROJECT);

        CoordinationRequest first = env.service.request("s1", "s2", "帮忙看一下构建", "task-1");
        CoordinationRequest duplicate = env.service.request("s1", "s2", "帮忙看一下构建", "task-1");
        assertEquals(first.id(), duplicate.id(), "重复提交同一请求要幂等");
        assertEquals(1, env.service.snapshot("s1").requests().size());

        assertEquals(CoordinationRefusedException.DENIED,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.acceptRequest("s1", first.id())).code());

        CoordinationRequest accepted = env.service.acceptRequest("s2", first.id());
        assertEquals(CoordinationRequestStatus.ACCEPTED, accepted.status());
        assertEquals(CoordinationRefusedException.CLOSED,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.declineRequest("s2", first.id())).code());

        CoordinationRequest next = env.service.request("s1", "s2", "第二轮", "task-1");
        assertFalse(next.id().equals(first.id()), "上一轮已结束，再次提交要新建请求");
        CoordinationRequest withdrawn = env.service.withdrawRequest("s1", next.id());
        assertEquals(CoordinationRequestStatus.WITHDRAWN, withdrawn.status());
        assertEquals(CoordinationRefusedException.CLOSED,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.withdrawRequest("s2", next.id())).code());

        CoordinationRequest third = env.service.request("s1", "s2", "第三轮", "task-2");
        assertEquals(CoordinationRefusedException.DENIED,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.withdrawRequest("s2", third.id())).code());
        assertEquals(CoordinationRequestStatus.PENDING, env.service.request("s1", "s2", "第三轮", "task-2").status());
    }

    @Test
    void handoffNeedsTargetAcceptanceAndExpiresInTime(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path shared = Files.createDirectories(root.resolve("shared"));
        env.project(PROJECT, List.of(shared));
        env.session("s1", shared, PROJECT);
        env.session("s2", shared, PROJECT);

        CoordinationHandoff handoff = env.service.handoff("s1", "s2", "task-9", "把迁移脚本做完");
        assertEquals(CoordinationRefusedException.DENIED,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.acceptHandoff("s1", handoff.id())).code());
        assertEquals(CoordinationHandoffStatus.ACCEPTED, env.service.acceptHandoff("s2", handoff.id()).status());

        CoordinationHandoff slow = env.service.handoff("s1", "s2", "task-10", "慢慢来");
        env.clock.advance(Duration.ofHours(3));
        CoordinationHandoff expired = env.service.snapshot("s2").handoffs().stream()
            .filter(entry -> entry.id().equals(slow.id())).findFirst().orElseThrow();
        assertEquals(CoordinationHandoffStatus.EXPIRED, expired.status());
        assertEquals(CoordinationRefusedException.CLOSED,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.acceptHandoff("s2", slow.id())).code());
        assertTrue(env.drafts.stream()
            .anyMatch(draft -> draft.type() == WorkspaceEventType.HANDOFF_RESOLVED
                && "EXPIRED".equals(draft.details().get("status"))));
    }

    @Test
    void differentProjectsStayIsolated(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path one = Files.createDirectories(root.resolve("one"));
        Path two = Files.createDirectories(root.resolve("two"));
        env.project("p1", List.of(one));
        env.project("p2", List.of(two));
        env.session("s1", one, "p1");
        env.session("s2", two, "p2");

        assertEquals(CoordinationRefusedException.OUT_OF_SCOPE,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.request("s1", "s2", "跨项目", "task-x")).code());
        assertEquals(CoordinationRefusedException.OUT_OF_SCOPE,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.handoff("s1", "s2", "task-x", "跨项目")).code());
        assertEquals(CoordinationRefusedException.OUT_OF_SCOPE,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.claim("s1", two.resolve("x.txt").toString(), CoordinationClaimKind.FILE,
                    null)).code());

        env.service.claim("s2", two.resolve("x.txt").toString(), CoordinationClaimKind.FILE, null);
        CoordinationSnapshot fromP1 = env.service.snapshot("s1");
        assertEquals(List.of("s1"), fromP1.sessions().stream().map(CoordinationSessionView::sessionId).toList());
        assertTrue(fromP1.claims().isEmpty(), "别的项目的占用不可见");
    }

    @Test
    void onlineStateIsRuntimeOnlyAndRequestsSurviveRestart(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path shared = Files.createDirectories(root.resolve("shared"));
        env.project(PROJECT, List.of(shared));
        env.session("s1", shared, PROJECT);
        env.session("s2", shared, PROJECT);
        env.online.add("s2");

        CoordinationRequest request = env.service.request("s1", "s2", "启动持久化检查", "");
        assertTrue(env.service.snapshot("s1").sessions().stream()
            .anyMatch(view -> view.sessionId().equals("s2") && view.online()));

        env.online.clear();
        SessionCoordinationService restarted = env.reopen();
        CoordinationSnapshot snapshot = restarted.snapshot("s1");
        assertTrue(snapshot.requests().stream().anyMatch(entry -> entry.id().equals(request.id())),
            "请求属于持久状态，重启后仍在");
        assertTrue(snapshot.sessions().stream().noneMatch(CoordinationSessionView::online),
            "重启后不能伪造在线会话");
    }

    @Test
    void sandboxOwnerGuardBlocksProxyOperations() {
        Env env = new Env();
        CoordinationRefusedException refused = assertThrows(CoordinationRefusedException.class,
            () -> env.service.requireSandboxOwner("s2", "s1"));
        assertEquals(CoordinationRefusedException.DENIED, refused.code());
        assertEquals("s1", refused.ownerSessionId());
        env.service.requireSandboxOwner("s1", "s1");
    }

    @Test
    void withoutProjectOnlyOwnWorkspaceIsCoordinateable(@TempDir Path root) throws IOException {
        Env env = new Env();
        Path mine = Files.createDirectories(root.resolve("mine"));
        Path other = Files.createDirectories(root.resolve("other"));
        env.session("s1", mine, null);
        env.session("s2", other, null);

        CoordinationClaim claim =
            env.service.claim("s1", mine.resolve("a.txt").toString(), CoordinationClaimKind.FILE, null);
        assertEquals(CoordinationRefusedException.OUT_OF_SCOPE,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.claim("s1", other.resolve("a.txt").toString(), CoordinationClaimKind.FILE,
                    null)).code());
        CoordinationSnapshot own = env.service.snapshot("s1");
        assertSame(1, own.claims().size());
        assertEquals(claim.id(), own.claims().getFirst().id());
        assertEquals(CoordinationRefusedException.OUT_OF_SCOPE,
            assertThrows(CoordinationRefusedException.class,
                () -> env.service.request("s1", "s2", "无项目互不可见", "")).code());
    }
}
