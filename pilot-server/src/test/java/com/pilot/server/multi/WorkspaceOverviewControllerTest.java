package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.run.RunRegistry;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.databind.json.JsonMapper;

class WorkspaceOverviewControllerTest {

    @TempDir
    Path workspace;

    @TempDir
    Path dataDir;

    @TempDir
    Path commandStorage;

    @Test
    void existingWorkspaceReturnsAnOverview() throws Exception {
        WorkspaceOverviewService.Overview overview = controller().overview(workspace.toString());

        assertEquals(workspace.toRealPath().toString(), overview.workspace());
        assertTrue(overview.sessions().isEmpty());
        assertTrue(overview.fileOwners().isEmpty());
    }

    @Test
    void missingWorkspaceIsNotFound() {
        WorkspaceOverviewController controller = controller();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.overview(workspace.resolve("missing").toString()));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private WorkspaceOverviewController controller() {
        return new WorkspaceOverviewController(new WorkspaceOverviewService(new InMemorySessionRepository(),
            new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString())), new RunRegistry(),
            new CommandTaskManager(commandStorage, JsonMapper.builder().build()), new InMemoryProjectRepository()));
    }
}
