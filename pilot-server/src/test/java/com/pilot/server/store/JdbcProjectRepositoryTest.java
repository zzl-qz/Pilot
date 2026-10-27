package com.pilot.server.store;

import com.pilot.core.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectRepositoryTest {
    @TempDir
    Path tempDir;

    private Path database;
    private JdbcProjectRepository repository;

    @BeforeEach
    void setUp() {
        database = tempDir.resolve("pilot.db");
        repository = new JdbcProjectRepository(new SqliteStore(database));
    }

    @Test
    void persistsProjectWithFoldersAcrossReopen() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        repository.create("prj_1", "pilot", List.of("E:\\work\\pilot\\server", "E:\\work\\pilot\\web"), now);

        var reopened = new JdbcProjectRepository(new SqliteStore(database));
        Project project = reopened.find("prj_1").orElseThrow();

        assertEquals("pilot", project.name());
        assertEquals(List.of("E:\\work\\pilot\\server", "E:\\work\\pilot\\web"), project.folders());
        // 第一个文件夹是新建对话的默认工作区
        assertEquals("E:\\work\\pilot\\server", project.primaryFolder());
    }

    @Test
    void replacesFoldersInOrder() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        repository.create("prj_1", "pilot", List.of("a", "b", "c"), now);

        assertTrue(repository.updateFolders("prj_1", List.of("c", "a"), now.plusSeconds(1)));

        Project project = repository.find("prj_1").orElseThrow();
        assertEquals(List.of("c", "a"), project.folders());
        assertEquals("c", project.primaryFolder());
    }

    @Test
    void renamesAndListsByUpdatedAt() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        repository.create("prj_1", "first", List.of("a"), now);
        repository.create("prj_2", "second", List.of("b"), now.plusSeconds(1));

        assertEquals(List.of("prj_2", "prj_1"), repository.list().stream().map(Project::id).toList());

        repository.rename("prj_1", "renamed", now.plusSeconds(5));
        assertEquals("renamed", repository.find("prj_1").orElseThrow().name());
        assertEquals(List.of("prj_1", "prj_2"), repository.list().stream().map(Project::id).toList());
    }

    @Test
    void deletesProjectAndItsFolders() {
        repository.create("prj_1", "pilot", List.of("a"), Instant.now());

        assertTrue(repository.delete("prj_1"));
        assertTrue(repository.find("prj_1").isEmpty());
        assertTrue(repository.list().isEmpty());
    }
}
