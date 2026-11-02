package com.pilot.server.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
