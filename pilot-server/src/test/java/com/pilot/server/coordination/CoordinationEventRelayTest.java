package com.pilot.server.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.coordination.ProjectCoordinationScope;
import com.pilot.core.coordination.WorkspaceCoordinationEvent;
import com.pilot.core.coordination.WorkspaceCoordinationEventBus;
import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SandboxDiffReady;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.Session;

/**
 * 会话与运行事件到工作区事件的映射：只验证映射与隔离，不涉及会话事件流本身。
 */
class CoordinationEventRelayTest {

    private final InMemorySessionRepository repository = new InMemorySessionRepository();
    private final WorkspaceCoordinationEventBus bus = new WorkspaceCoordinationEventBus();
    private final CoordinationEventRelay relay = new CoordinationEventRelay(bus, repository);

    @Test
    void sessionStartedAndStoppedCarryProjectAndDirectory(@TempDir Path root) {
        Session session = session("p1", root);

        relay.started(session);
        relay.stopped(session);

        List<WorkspaceCoordinationEvent> events = bus.replay(0, scope("p1", root), 10);
        assertEquals(List.of("session.started", "session.stopped"),
            events.stream().map(WorkspaceCoordinationEvent::wireType).toList());
        assertEquals("p1", events.getFirst().projectId());
        assertEquals(root.toString(), events.getFirst().directory());
        assertEquals(session.id(), events.getFirst().sessionId());
    }

    @Test
    void runLifecycleIsProjectedWithoutUserInput(@TempDir Path root) {
        Session session = session("p1", root);

        relay.sessionEvent(session.id(), "run_1", new RunStarted("这段输入属于会话私有内容"));
        relay.sessionEvent(session.id(), "run_1", new RunCompleted("完成", List.of("a/Secret.java"), List.of()));

        List<WorkspaceCoordinationEvent> events = bus.replay(0, scope("p1", root), 10);
        assertEquals(List.of("run.started", "run.finished"),
            events.stream().map(WorkspaceCoordinationEvent::wireType).toList());
        assertEquals("run_1", events.getFirst().details().get("runId"));
        // 会话输入与变更文件清单不能进工作区事件
        assertFalse(events.getFirst().toString().contains("会话私有内容"));
        assertFalse(events.getLast().toString().contains("Secret.java"));
        assertEquals("1", events.getLast().details().get("changedFiles"));
    }

    @Test
    void failuresAndCancellationsFinishTheRunWithCode(@TempDir Path root) {
        Session session = session("p1", root);

        relay.sessionEvent(session.id(), "run_1", new RunFailed("MAX_STEPS", "步数用尽", false));
        relay.sessionEvent(session.id(), "run_2", new RunCancelled("用户停止"));

        List<WorkspaceCoordinationEvent> events = bus.replay(0, scope("p1", root), 10);
        assertEquals(List.of("run.finished", "run.finished"),
            events.stream().map(WorkspaceCoordinationEvent::wireType).toList());
        assertEquals("MAX_STEPS", events.getFirst().details().get("code"));
        assertEquals("用户停止", events.getLast().details().get("reason"));
        assertEquals("run_2", events.getLast().details().get("runId"));
    }

    @Test
    void sandboxAndArbitrationChangesArePublished(@TempDir Path root) {
        Session session = session("p1", root);

        relay.sessionEvent(session.id(), "run_1", new SandboxDiffReady("sb_1", "ag_1", "t_1", "src/App.java", 3, 1));
        relay.sessionEvent(session.id(), "run_1",
            new ArbitrationRequired("arb_1", "conflict", "t_1", List.of("保留本会话", "接受对方"), "仲裁报告正文"));
        relay.sessionEvent(session.id(), "run_1", new ArbitrationResolved("arb_1", "接受对方"));

        List<WorkspaceCoordinationEvent> events = bus.replay(0, scope("p1", root), 10);
        assertEquals(List.of("sandbox.changed", "arbitration.created", "arbitration.resolved"),
            events.stream().map(WorkspaceCoordinationEvent::wireType).toList());
        assertEquals("sb_1", events.getFirst().details().get("sandboxId"));
        assertEquals("src/App.java", events.getFirst().details().get("path"));
        assertEquals("arb_1", events.get(1).details().get("arbitrationId"));
        assertFalse(events.get(1).toString().contains("仲裁报告正文"));
        assertEquals("接受对方", events.getLast().details().get("choice"));
    }

    @Test
    void unrelatedSessionEventsAreIgnored(@TempDir Path root) {
        Session session = session("p1", root);

        relay.sessionEvent(session.id(), "run_1", new AssistantDelta("很长的正文"));

        assertEquals(0, bus.latestSequence());
        assertTrue(bus.replay(0, scope("p1", root), 10).isEmpty());
    }

    @Test
    void unknownSessionPublishesNothing(@TempDir Path root) {
        relay.sessionEvent("ses_missing", "run_1", new RunStarted("x"));

        assertEquals(0, bus.latestSequence());
    }

    @Test
    void sessionWithoutProjectStillPublishesDirectoryScopedEvent(@TempDir Path root) {
        Session session = repository.create("ses_personal", "个人会话", root.toString(), Instant.now(), null, false, null);

        relay.started(session);

        WorkspaceCoordinationEvent event =
            bus.replay(0, ProjectCoordinationScope.of(null, List.of(root), true), 10).getFirst();
        assertNull(event.projectId());
        assertEquals("session.started", event.wireType());
    }

    @Test
    void publishingFailureDoesNotBreakTheCaller(@TempDir Path root) {
        var failing = new CoordinationEventRelay(draft -> {
            throw new IllegalStateException("总线不可用");
        }, repository);
        Session session = session("p1", root);

        failing.started(session);
        failing.sessionEvent(session.id(), "run_1", new RunStarted("x"));

        assertEquals(0, bus.latestSequence());
    }

    private Session session(String projectId, Path directory) {
        return repository.create("ses_1", "会话", directory.toString(), Instant.now(), projectId, false, null);
    }

    private static ProjectCoordinationScope scope(String projectId, Path directory) {
        return ProjectCoordinationScope.of(projectId, List.of(directory), false);
    }
}
