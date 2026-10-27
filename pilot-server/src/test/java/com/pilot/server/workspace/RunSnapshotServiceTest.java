package com.pilot.server.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunSnapshotServiceTest {
    @TempDir
    Path workspace;

    @TempDir
    Path storage;

    private RunSnapshotService snapshots;

    @BeforeEach
    void setUp() {
        snapshots = new RunSnapshotService(JsonMapper.builder().build(), storage);
    }

    @Test
    void restoresModifiedFileAndDeletesCreatedFile() throws IOException {
        Path existing = workspace.resolve("a.txt");
        Files.writeString(existing, "original");

        snapshots.bind("run_1", workspace);
        snapshots.beforeWrite(existing);
        Files.writeString(existing, "changed once");
        snapshots.beforeWrite(existing);
        Files.writeString(existing, "changed twice");

        Path created = workspace.resolve("dir/b.txt");
        Files.createDirectories(created.getParent());
        snapshots.beforeWrite(created);
        Files.writeString(created, "new file");
        snapshots.clear();

        RunSnapshotService.RollbackReport report = snapshots.rollback("run_1");

        assertEquals("original", Files.readString(existing), "多次写入只应还原到运行前的内容");
        assertFalse(Files.exists(created));
        assertEquals(List.of("a.txt"), report.restored());
        assertEquals(List.of("dir/b.txt"), report.deleted());
        assertTrue(report.failed().isEmpty());
    }

    @Test
    void ignoresWritesWhenNoRunIsBound() throws IOException {
        Path file = workspace.resolve("c.txt");
        Files.writeString(file, "keep");

        snapshots.beforeWrite(file);

        assertThrows(IllegalArgumentException.class, () -> snapshots.rollback("run_x"));
    }

    @Test
    void exposesRecordedFilesForChangeReview() throws IOException {
        Path existing = workspace.resolve("a.txt");
        Files.writeString(existing, "origin");

        snapshots.bind("run_meta", workspace);
        snapshots.beforeWrite(existing);
        snapshots.beforeWrite(workspace.resolve("dir/new.txt"));
        snapshots.clear();

        RunSnapshotService.SnapshotMeta meta = snapshots.meta("run_meta");

        assertNotNull(meta);
        assertEquals("run_meta", meta.runId());
        assertEquals(List.of(
                new RunSnapshotService.SnapshotEntry("a.txt", true),
                new RunSnapshotService.SnapshotEntry("dir/new.txt", false)), meta.files());
        assertNull(snapshots.meta("run_absent"), "没写过文件时没有元数据，交给上层按「无改动」处理");
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        Path outside = storage.resolve("outside.txt");

        snapshots.bind("run_2", workspace);
        try {
            assertThrows(IllegalStateException.class, () -> snapshots.beforeWrite(outside));
        } finally {
            snapshots.clear();
        }
    }
}
