package com.pilot.server.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.coordination.CoordinationClaim;
import com.pilot.core.coordination.CoordinationClaimKind;
import com.pilot.core.coordination.CoordinationHandoff;
import com.pilot.core.coordination.CoordinationHandoffStatus;
import com.pilot.core.coordination.CoordinationRefusedException;
import com.pilot.core.coordination.CoordinationRequest;
import com.pilot.core.coordination.CoordinationRequestStatus;
import com.pilot.core.coordination.CoordinationScopeResolver;
import com.pilot.core.coordination.CoordinationSettings;
import com.pilot.core.coordination.CoordinationSnapshotStore;
import com.pilot.core.coordination.SessionCoordinationService;
import com.pilot.core.coordination.WorkspaceCoordinationEvent;
import com.pilot.core.coordination.WorkspaceEventDraft;
import com.pilot.core.coordination.WorkspaceEventPublisher;
import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.multi.WorkspaceOverviewService;
import com.pilot.server.run.RunRegistry;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.databind.json.JsonMapper;

/** 协调接口的状态码、越权拒绝与在线登记；协议规则本身由 SessionCoordinationServiceTest 覆盖。 */
class SessionCoordinationControllerTest {

    @TempDir
    Path root;

    @TempDir
    Path commandStorage;

    private InMemorySessionRepository sessions;
    private InMemoryProjectRepository projects;
    private SessionPresenceRegistry presence;
    private SessionCoordinationController controller;
    private Path primary;
    private Path attached;

    @BeforeEach
    void setUp() throws IOException {
        primary = Files.createDirectories(root.resolve("primary"));
        attached = Files.createDirectories(root.resolve("attached"));
        sessions = new InMemorySessionRepository();
        projects = new InMemoryProjectRepository();
        projects.create("p1", "项目", List.of(primary.toString(), attached.toString()),
            Instant.parse("2026-01-01T00:00:00Z"));
        presence = new SessionPresenceRegistry(60_000);
        List<WorkspaceEventDraft> drafts = new ArrayList<>();
        WorkspaceEventPublisher publisher = draft -> {
            drafts.add(draft);
            return new WorkspaceCoordinationEvent(drafts.size(), Instant.now(), draft.type(), draft.projectId(),
                draft.sessionId(), draft.directory(), draft.subject(), draft.details());
        };
        CoordinationSnapshotStore store =
            new CoordinationStateStore(JsonMapper.builder().build(), new PilotDataDir(root.resolve("data").toString()));
        SessionCoordinationService coordination =
            new SessionCoordinationService(sessions, new CoordinationScopeResolver(projects, false), publisher, store,
                new CoordinationSettings(presence, Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofHours(2)));
        WorkspaceOverviewService overview = new WorkspaceOverviewService(sessions,
            new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(root.resolve("data").toString())),
            new RunRegistry(), new CommandTaskManager(commandStorage, JsonMapper.builder().build()), projects);
        controller = new SessionCoordinationController(coordination, sessions, overview, presence);
    }

    private void session(String id, Path workspace) {
        sessions.create(id, "会话 " + id, workspace.toString(), Instant.now(), "p1", false);
    }

    @Test
    void boardShowsSharedFolderSessionsAndOccupancy() {
        session("s1", primary);
        session("s2", attached);
        CoordinationClaim claim = controller.claim("s1",
            new SessionCoordinationController.ClaimCommand(primary.resolve("App.java").toString(), "file", null));

        SessionCoordinationController.CoordinationBoard board = controller.board("s2");

        assertEquals(2, board.snapshot().sessions().size(), "主目录不同的两个会话因共享项目文件夹而互相可见");
        assertEquals(List.of(claim.id()), board.snapshot().claims().stream().map(CoordinationClaim::id).toList());
        assertNotNull(board.overview(), "同一项目下应能拿到工作区概览");
    }

    @Test
    void boardReadersAppearOnline() {
        session("s1", primary);
        session("s2", attached);

        controller.touch("s1");
        controller.touch("s2");
        SessionCoordinationController.CoordinationBoard board = controller.board("s2");

        assertTrue(
            board.snapshot().sessions().stream().anyMatch(view -> view.sessionId().equals("s1") && view.online()),
            "打开看板即在线");
        assertEquals(2, presence.metrics().get("online"));
    }

    @Test
    void requestDecisionsRespectTargetAndRequesterRoles() {
        session("s1", primary);
        session("s2", attached);
        CoordinationRequest request =
            controller.request("s1", new SessionCoordinationController.RequestCommand("s2", "帮忙跑回填", "task-7"));

        ResponseEntity<SessionCoordinationController.Refusal> denied =
            refusalOf(() -> controller.resolveRequest("s1", request.id(), "accept"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals(CoordinationRefusedException.DENIED, denied.getBody().code());
        assertEquals("s2", denied.getBody().ownerSessionId());

        CoordinationRequest accepted = controller.resolveRequest("s2", request.id(), "accept");
        assertEquals(CoordinationRequestStatus.ACCEPTED, accepted.status());
        assertEquals(HttpStatus.CONFLICT,
            refusalOf(() -> controller.resolveRequest("s2", request.id(), "decline")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            refusalOf(() -> controller.resolveRequest("s2", "req-missing", "accept")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> controller.resolveRequest("s2", request.id(), "explode"))
                .getStatusCode());
    }

    @Test
    void claimConflictCarriesOwnerAndStatus() {
        session("s1", primary);
        session("s2", attached);
        controller.claim("s1", new SessionCoordinationController.ClaimCommand(attached.toString(), "directory", null));

        ResponseEntity<SessionCoordinationController.Refusal> conflict = refusalOf(() -> controller.claim("s2",
            new SessionCoordinationController.ClaimCommand(attached.resolve("deep/File.java").toString(), null, null)));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(CoordinationRefusedException.CLAIMED, conflict.getBody().code());
        assertEquals("s1", conflict.getBody().ownerSessionId());
        assertEquals(controller.board("s2").snapshot().claims().getFirst().path(), conflict.getBody().path(),
            "拒绝里带的是占用方所在位置");
    }

    @Test
    void claimWithoutKindDefaultsToFile() {
        session("s1", primary);

        CoordinationClaim claim = controller.claim("s1",
            new SessionCoordinationController.ClaimCommand(primary.resolve("a.txt").toString(), null, null));

        assertEquals(CoordinationClaimKind.FILE, claim.kind(), "不传类型时按文件占用");
    }

    @Test
    void onlyTheClaimerMayRelease() {
        session("s1", primary);
        session("s2", attached);
        CoordinationClaim claim = controller.claim("s1",
            new SessionCoordinationController.ClaimCommand(primary.resolve("a.txt").toString(), "file", null));

        assertEquals(HttpStatus.FORBIDDEN, refusalOf(() -> controller.release("s2", claim.id())).getStatusCode());
        assertEquals(claim.id(), controller.release("s1", claim.id()).id());
        assertEquals(HttpStatus.NOT_FOUND, refusalOf(() -> controller.release("s1", claim.id())).getStatusCode());
    }

    @Test
    void claimTtlSecondsBecomeLease() {
        session("s1", primary);
        CoordinationClaim claim = controller.claim("s1",
            new SessionCoordinationController.ClaimCommand(primary.resolve("b.txt").toString(), "file", 60L));

        assertFalse(claim.expiredAt(Instant.now()), "传秒数后仍应是未过期租约");
        assertTrue(claim.expiresAt().isBefore(Instant.now().plus(Duration.ofMinutes(5))), "租约按传入秒数收紧");
    }

    @Test
    void handoffOnlyTargetMayDecideAndItStaysVisibleOnBoard() {
        session("s1", primary);
        session("s2", attached);
        CoordinationHandoff handoff =
            controller.handoff("s1", new SessionCoordinationController.HandoffCommand("s2", "task-3", "接手前端"));

        assertEquals(HttpStatus.FORBIDDEN,
            refusalOf(() -> controller.resolveHandoff("s1", handoff.id(), "accept")).getStatusCode());
        CoordinationHandoff declined = controller.resolveHandoff("s2", handoff.id(), "decline");
        assertEquals(CoordinationHandoffStatus.DECLINED, declined.status());
        assertEquals(declined.id(), controller.board("s2").snapshot().handoffs().getFirst().id());
    }

    @Test
    void unclaimedCheckAndUnknownKind() {
        session("s1", primary);
        session("s2", attached);
        controller.claim("s1",
            new SessionCoordinationController.ClaimCommand(primary.resolve("a.txt").toString(), "file", null));

        assertEquals(HttpStatus.CONFLICT, refusalOf(() -> controller.requireUnclaimed("s2",
            new SessionCoordinationController.PathCheck(primary.resolve("a.txt").toString()))).getStatusCode());
        assertEquals(false, controller
            .requireUnclaimed("s2", new SessionCoordinationController.PathCheck(attached.resolve("b.txt").toString()))
            .get("claimed"));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> controller.claim("s2",
                new SessionCoordinationController.ClaimCommand(attached.resolve("c.txt").toString(), "galaxy", null)))
                .getStatusCode());
    }

    private ResponseEntity<SessionCoordinationController.Refusal> refusalOf(Runnable call) {
        CoordinationRefusedException refused = assertThrows(CoordinationRefusedException.class, call::run);
        return controller.refused(refused);
    }
}
