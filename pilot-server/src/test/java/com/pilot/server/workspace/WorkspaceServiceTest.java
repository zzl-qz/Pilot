package com.pilot.server.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.store.PilotDataDir;

class WorkspaceServiceTest {
    @Test
    void rootShortcutsRespectServerPlatformCaseRules(@TempDir Path temp) {
        var entries = WorkspaceService.roots(temp.resolve("Project"), temp.resolve("project"), List.of());
        assertEquals(File.separatorChar == '\\' ? 1 : 2, entries.size());
        assertEquals("主目录", entries.getFirst().label());
    }

    @Test
    void equivalentRootsAreDeduplicatedWithoutLosingTheHomeLabel(@TempDir Path temp) {
        var entries = WorkspaceService.roots(temp, temp.resolve("."), List.of(temp, temp.getRoot()));
        assertEquals(2, entries.size());
        assertEquals("主目录", entries.getFirst().label());
        assertEquals(temp.getRoot().toString(), entries.getLast().path());
    }

    @Test
    void restrictsFileAccessToRegisteredWorkspacesAndBlocksDotPilot(@TempDir Path temp) throws Exception {
        Path allowed = Files.createDirectories(temp.resolve("allowed-project"));
        Path unregistered = Files.createDirectories(temp.resolve("unregistered-dir"));
        Path normalFile = Files.writeString(allowed.resolve("README.md"), "ok");
        Path dotPilot = Files.createDirectories(allowed.resolve(".pilot"));
        Files.writeString(dotPilot.resolve("pilot.db"), "secret");

        AgentProperties props = new AgentProperties(allowed.toString(), null, 0, null, null, 0, 0, null, null, null);
        WorkspaceService service = new WorkspaceService(props, new InMemoryProjectRepository(),
            new InMemorySessionRepository(), new PilotDataDir(dotPilot.toString()));

        assertEquals(allowed.toRealPath(), service.requireRegisteredWorkspace(allowed.toString()));
        assertThrows(IllegalArgumentException.class, () -> service.requireRegisteredWorkspace(unregistered.toString()));
        assertEquals(normalFile.toRealPath(), service.requireSafeWorkspaceFile(allowed, "README.md"));
        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
            () -> service.requireSafeWorkspaceFile(allowed, ".pilot/pilot.db"));
        assertTrue(blocked.getMessage().contains(".pilot"));
    }
}
