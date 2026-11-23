package com.pilot.server.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.coordination.CoordinationClaimKind;
import com.pilot.core.coordination.CoordinationRequest;
import com.pilot.core.coordination.CoordinationRequestStatus;
import com.pilot.core.coordination.CoordinationScopeResolver;
import com.pilot.core.coordination.CoordinationSettings;
import com.pilot.core.coordination.CoordinationSnapshot;
import com.pilot.core.coordination.CoordinationSnapshotStore;
import com.pilot.core.coordination.SessionCoordinationService;
import com.pilot.core.coordination.WorkspaceCoordinationEvent;
import com.pilot.core.coordination.WorkspaceEventDraft;
import com.pilot.core.coordination.WorkspaceEventPublisher;
import com.pilot.core.coordination.WorkspaceEventType;
import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionStatus;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.databind.ObjectMapper;

class CoordinationWatchdogServerTest {

    @Test
    void watchdogRespectsDisableFlagAndPatrolsWhenEnabled(@TempDir Path root) {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Instant now = Instant.parse("2026-11-23T10:00:00Z");
        projects.create("p1", "项目 p1", List.of(root.toString()), now);
        Session alpha = sessions.create("ses_alpha", "会话 A", root.toString(), now, "p1", false);
        List<WorkspaceEventDraft> published = new ArrayList<>();
        WorkspaceEventPublisher publisher = draft -> {
            published.add(draft);
            return new WorkspaceCoordinationEvent(published.size(), now, draft.type(), draft.projectId(),
                draft.sessionId(), draft.directory(), draft.subject(), draft.details());
        };
        CoordinationSnapshotStore.InMemory store = new CoordinationSnapshotStore.InMemory();
        SessionCoordinationService service = new SessionCoordinationService(sessions,
            new CoordinationScopeResolver(projects, false), publisher, store, CoordinationSettings.defaults());

        service.claim(alpha.id(), root.resolve("a.java").toString(), CoordinationClaimKind.FILE, null);
        sessions.updateStatus(alpha.id(), SessionStatus.INTERRUPTED);

        CoordinationWatchdog watchdog = new CoordinationWatchdog(service);
        String previous = System.getProperty("pilot.coordination.watchdog");
        try {
            System.setProperty("pilot.coordination.watchdog", "false");
            watchdog.patrol();
            assertEquals(1, store.load().claims().size(), "关闭看门狗开关时不应主动清扫");
            assertTrue(published.stream().noneMatch(e -> e.type() == WorkspaceEventType.FILE_RELEASED));

            System.clearProperty("pilot.coordination.watchdog");
            watchdog.patrol();
            assertTrue(store.load().claims().isEmpty(), "开启看门狗巡检后应主动释放中断会话占用");
            assertTrue(published.stream().anyMatch(e -> e.type() == WorkspaceEventType.FILE_RELEASED
                && SessionCoordinationService.OWNER_INACTIVE.equals(e.details().get("reason"))));
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.coordination.watchdog");
            } else {
                System.setProperty("pilot.coordination.watchdog", previous);
            }
        }
    }

    @Test
    void stateStoreRoundTripsRequestExpiresAt(@TempDir Path dataDirRoot) {
        CoordinationStateStore store =
            new CoordinationStateStore(new ObjectMapper(), new PilotDataDir(dataDirRoot.toString()));
        Instant created = Instant.parse("2026-11-23T10:00:00Z");
        Instant expires = Instant.parse("2026-11-23T10:45:00Z");
        CoordinationRequest request = new CoordinationRequest("req_1", "p1", "ses_a", "ses_b", "请协助核对", "T-1",
            CoordinationRequestStatus.PENDING, created, created, expires);
        store.save(new CoordinationSnapshot("p1", List.of(), List.of(), List.of(request), List.of(), List.of()));

        CoordinationSnapshot loaded = store.load();
        assertEquals(1, loaded.requests().size());
        assertEquals(expires, loaded.requests().getFirst().expiresAt());
    }
}
