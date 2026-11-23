package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionStatus;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.run.RunRegistry;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.databind.json.JsonMapper;

class WorkspaceOverviewServiceTest {

    private static final String ORIGINAL = "a\nb\nc\n";

    @TempDir
    Path workspace;

    @TempDir
    Path otherWorkspace;

    @TempDir
    Path dataDir;

    @TempDir
    Path commandStorage;

    @TempDir
    Path attached;

    @TempDir
    Path otherProjectRoot;

    @Test
    void groupsSessionsByWorkspaceAndListsOnlyOverlappingFiles() throws Exception {
        SessionRepository sessions = new InMemorySessionRepository();
        sessions.create("ses_a", "会话 A", workspace.toString(), Instant.now());
        sessions.create("ses_b", "会话 B", workspace.toString(), Instant.now());
        sessions.create("ses_c", "另一个工作区", otherWorkspace.toString(), Instant.now());
        SandboxStore sandboxes = sandboxes();
        sandboxes.add("ses_a", sandbox("sbx_a", "T1", "src/A.java"));
        sandboxes.add("ses_b", sandbox("sbx_b", "T2", "src/A.java"));
        sandboxes.add("ses_c", sandbox("sbx_c", "T3", "src/C.java"));

        WorkspaceOverviewService.Overview overview =
            service(sessions, sandboxes, "ses_a").overview(workspace.toString());

        assertEquals(List.of("ses_a", "ses_b"),
            overview.sessions().stream().map(WorkspaceOverviewService.SessionView::sessionId).toList());
        assertEquals(List.of("src/A.java"),
            overview.fileOwners().stream().map(WorkspaceOverviewService.FileOwner::path).toList());
        assertEquals(Set.of("ses_a", "ses_b"), Set.copyOf(overview.fileOwners().getFirst().sessions()));
        assertTrue(overview.sessions().getFirst().running());
        assertFalse(overview.sessions().getLast().running());
        assertEquals(List.of("src/A.java"), overview.sessions().getFirst().pendingFiles());
    }

    @Test
    void reportsPendingArbitrationAndDropsResolvedOnes() throws Exception {
        SessionRepository sessions = new InMemorySessionRepository();
        sessions.create("ses_a", "会话 A", workspace.toString(), Instant.now());
        sessions.append("ses_a", "run-a", Instant.now(),
            new ArbitrationRequired("arb-1", "cross-session", "T1", List.of("等", "改"), "跨会话占用"), SessionStatus.RUNNING);
        sessions.append("ses_a", "run-a", Instant.now(),
            new ArbitrationRequired("arb-2", "dissent", "T2", List.of(), "僵持"), SessionStatus.RUNNING);
        sessions.append("ses_a", "run-a", Instant.now(), new ArbitrationResolved("arb-2", "选 A"),
            SessionStatus.RUNNING);

        WorkspaceOverviewService.Overview overview =
            service(sessions, sandboxes(), null).overview(workspace.toString());

        List<WorkspaceOverviewService.ArbitrationView> arbitrations = overview.sessions().getFirst().arbitrations();
        assertEquals(1, arbitrations.size());
        assertEquals("arb-1", arbitrations.getFirst().arbitrationId());
        assertEquals("cross-session", arbitrations.getFirst().kind());
    }

    @Test
    void findsSessionsThatOnlyShareAnAttachedFolder() throws Exception {
        SessionRepository sessions = new InMemorySessionRepository();
        sessions.create("ses_a", "会话 A", workspace.toString(), Instant.now(), "p1");
        sessions.create("ses_b", "会话 B", otherWorkspace.toString(), Instant.now(), "p1");
        sessions.create("ses_c", "会话 C", attached.toString(), Instant.now(), "p1");
        InMemoryProjectRepository projects = projects("p1", workspace, otherWorkspace, attached);
        SandboxStore sandboxes = sandboxes();
        sandboxes.add("ses_a", sandbox("sbx_a", "T1", workspace, "src/A.java"));
        sandboxes.add("ses_b", sandbox("sbx_b", "T2", otherWorkspace, "src/B.java"));
        sandboxes.add("ses_c", sandbox("sbx_c", "T3", attached, "docs/api.md"));

        WorkspaceOverviewService.Overview overview =
            service(sessions, sandboxes, null, projects).overview(workspace.toString());

        assertEquals(List.of("ses_a", "ses_b", "ses_c"),
            overview.sessions().stream().map(WorkspaceOverviewService.SessionView::sessionId).toList());
        assertEquals("p1", overview.projectId());
        assertEquals(3, overview.coordinationDirectories().size(), overview.coordinationDirectories().toString());
        assertEquals(List.of("src/B.java"), overview.sessions().get(1).pendingFiles());
        assertEquals(List.of("docs/api.md"), overview.sessions().get(2).pendingFiles());

        // 从附加目录打开看板，同样能看到同项目里主目录不同的会话
        assertEquals(List.of("ses_a", "ses_b", "ses_c"),
            service(sessions, sandboxes, null, projects).overview(attached.toString()).sessions().stream()
                .map(WorkspaceOverviewService.SessionView::sessionId).toList());
    }

    @Test
    void keepsSessionsOfAnotherProjectOutOfTheOverview() throws Exception {
        SessionRepository sessions = new InMemorySessionRepository();
        sessions.create("ses_a", "会话 A", workspace.toString(), Instant.now(), "p1");
        sessions.create("ses_b", "会话 B", otherProjectRoot.toString(), Instant.now(), "p2");
        InMemoryProjectRepository projects = projects("p1", workspace);
        projects.create("p2", "另一个项目", List.of(otherProjectRoot.toString()), Instant.now());
        SandboxStore sandboxes = sandboxes();
        sandboxes.add("ses_a", sandbox("sbx_a", "T1", workspace, "src/A.java"));
        sandboxes.add("ses_b", sandbox("sbx_b", "T2", otherProjectRoot, "src/A.java"));

        WorkspaceOverviewService.Overview overview =
            service(sessions, sandboxes, null, projects).overview(workspace.toString());

        assertEquals(List.of("ses_a"),
            overview.sessions().stream().map(WorkspaceOverviewService.SessionView::sessionId).toList());
        assertTrue(overview.fileOwners().isEmpty(), overview.fileOwners().toString());
    }

    private WorkspaceOverviewService service(SessionRepository sessions, SandboxStore sandboxes,
        String runningSession) {
        return service(sessions, sandboxes, runningSession, new InMemoryProjectRepository());
    }

    private WorkspaceOverviewService service(SessionRepository sessions, SandboxStore sandboxes, String runningSession,
        InMemoryProjectRepository projects) {
        RunRegistry runs = new RunRegistry();
        if (runningSession != null) {
            runs.register("run-" + runningSession, runningSession);
        }
        return new WorkspaceOverviewService(sessions, sandboxes, runs, commands(), projects);
    }

    private InMemoryProjectRepository projects(String projectId, Path... folders) {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        projects.create(projectId, "项目", Arrays.stream(folders).map(Path::toString).toList(), Instant.now());
        return projects;
    }

    private CommandTaskManager commands() {
        return new CommandTaskManager(commandStorage, JsonMapper.builder().build());
    }

    private SandboxStore sandboxes() {
        return new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString()));
    }

    private DiffSandbox sandbox(String sandboxId, String taskId, String path) throws IOException {
        return sandbox(sandboxId, taskId, workspace, path);
    }

    private DiffSandbox sandbox(String sandboxId, String taskId, Path root, String path) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ORIGINAL);
        DiffSandbox sandbox = new DiffSandbox(sandboxId, "agent", taskId, "", root);
        sandbox.add(path, ORIGINAL, "changed\n", true, RiskLevel.MEDIUM);
        return sandbox;
    }
}
