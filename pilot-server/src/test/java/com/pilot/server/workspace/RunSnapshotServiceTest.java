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
        snapshots.finish();
        snapshots.clear();

        RunSnapshotService.RollbackReport report = snapshots.rollback("run_1");

        assertEquals("original", Files.readString(existing), "多次写入只应还原到运行前的内容");
        assertFalse(Files.exists(created));
        assertEquals(List.of("a.txt"), report.restored());
        assertEquals(List.of("dir/b.txt"), report.deleted());
        assertTrue(report.failed().isEmpty());
    }

    @Test
    void restoresRunResultWhenItHasNotBeenModifiedSinceCompletion() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "original");
        snapshots.bind("run_completed", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "agent change");
        snapshots.finish();
        snapshots.clear();

        RunSnapshotService.RollbackReport report = snapshots.rollback("run_completed");

        assertTrue(report.conflicts().isEmpty());
        assertEquals("original", Files.readString(file));
    }

    @Test
    void refusesRollbackWhenFileWasModifiedAfterRun() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "original");

        snapshots.bind("run_conflict", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "agent change");
        snapshots.finish();
        snapshots.clear();
        Files.writeString(file, "manual edit");

        RunSnapshotService.RollbackReport report = snapshots.rollback("run_conflict");

        assertEquals(List.of("a.txt"), report.conflicts());
        assertTrue(report.restored().isEmpty());
        assertEquals("manual edit", Files.readString(file));
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
        assertEquals(List.of("a.txt", "dir/new.txt"), meta.files().stream()
                .map(RunSnapshotService.SnapshotEntry::path).toList());
        assertTrue(meta.files().get(0).originalHash() != null);
        assertNull(meta.files().get(1).originalHash());
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
    @Test
    void restoresAllLaterRunsToEarliestStateAndPreflightsEveryFile() throws IOException {
        Path file = workspace.resolve("a.txt");
        Path later = workspace.resolve("later.txt");
        Files.writeString(file, "before");
        change("chain_1", file, "first");
        snapshots.bind("chain_2", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "second");
        snapshots.beforeWrite(later);
        Files.writeString(later, "new");
        snapshots.finish();
        snapshots.clear();
        assertTrue(snapshots.preview(List.of("chain_1", "chain_2")).ready());
        Files.writeString(later, "manual");
        var blocked = snapshots.rollback(List.of("chain_1", "chain_2"));
        assertEquals(List.of("later.txt"), blocked.conflicts());
        assertEquals("second", Files.readString(file), "任何冲突都应在写文件前拦截");
        Files.writeString(later, "new");
        var restored = snapshots.rollback(List.of("chain_1", "chain_2"));
        assertTrue(restored.failed().isEmpty());
        assertEquals("before", Files.readString(file));
        assertFalse(Files.exists(later));
    }

    @Test
    void detectsManualEditsBetweenRunsEvenIfLatestResultMatches() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "before");
        change("gap_1", file, "first");
        Files.writeString(file, "manual between runs");
        change("gap_2", file, "second");
        assertEquals(List.of("a.txt"), snapshots.rollback(List.of("gap_1", "gap_2")).conflicts());
        assertEquals("second", Files.readString(file));
    }

    @Test
    void missingBackupBlocksEveryFileAndIncompleteRunsCannotRestore() throws IOException {
        Path first = workspace.resolve("a.txt");
        Path second = workspace.resolve("b.txt");
        Files.writeString(first, "old a");
        Files.writeString(second, "old b");
        snapshots.bind("missing_backup", workspace);
        snapshots.beforeWrite(first);
        snapshots.beforeWrite(second);
        Files.writeString(first, "new a");
        Files.writeString(second, "new b");
        assertFalse(snapshots.preview(List.of("missing_backup")).ready());
        snapshots.finish();
        snapshots.clear();
        Files.delete(storage.resolve("missing_backup/files/b.txt"));
        assertEquals(List.of("b.txt"), snapshots.rollback("missing_backup").failed());
        assertEquals("new a", Files.readString(first));
    }

    @Test
    void refusesSymlinkedParentAndDirectoryReplacingNewFile() throws IOException {
        Path file = workspace.resolve("dir/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "before");
        change("linked_parent", file, "after");
        Path moved = workspace.resolve("moved");
        Files.move(file.getParent(), moved);
        Files.createSymbolicLink(file.getParent(), moved);
        assertEquals(List.of("dir/a.txt"), snapshots.rollback("linked_parent").conflicts());
        assertEquals("after", Files.readString(moved.resolve("a.txt")));
        Path created = workspace.resolve("new.txt");
        change("directory_collision", created, "new");
        Files.delete(created);
        Files.createDirectory(created);
        assertEquals(List.of("new.txt"), snapshots.rollback("directory_collision").conflicts());
    }

    @Test
    void conversationCheckpointSurvivesServiceRestartWithExactMessages() {
        var messages = List.of(com.pilot.core.loop.Message.user("重复"),
                com.pilot.core.loop.Message.assistant("之前的回复", List.of()),
                com.pilot.core.loop.Message.user("重复"));
        snapshots.saveConversation("conversation", messages, List.of());
        var reopened = new RunSnapshotService(JsonMapper.builder().build(), storage);
        assertEquals(messages, reopened.conversation("conversation").messages());
        snapshots.bind("empty", workspace);
        snapshots.finish();
        snapshots.clear();
        assertTrue(reopened.preview(List.of("empty")).ready(), "无文件改动和已过期的快照必须能区分");
        assertFalse(reopened.preview(List.of("expired")).ready());
    }

    @Test
    void preservesManualEditsMadeBeforeRunFinishes() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "before");
        snapshots.bind("mid_run_manual", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "agent");
        snapshots.afterWrite(file);
        Files.writeString(file, "manual while model thinks");
        snapshots.finish();
        snapshots.clear();
        assertEquals(List.of("a.txt"), snapshots.rollback("mid_run_manual").conflicts());
        assertEquals("manual while model thinks", Files.readString(file));
    }

    @Test
    void legacyMetadataIsUnavailableInsteadOfOverwritingUnverifiedFiles() throws IOException {
        Path directory = storage.resolve("legacy");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("meta.json"),
                "{\"runId\":\"legacy\",\"workspace\":\"/tmp\",\"files\":[]}");
        assertFalse(snapshots.preview(List.of("legacy")).ready());
    }

    private void change(String runId, Path file, String content) throws IOException {
        snapshots.bind(runId, workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, content);
        snapshots.finish();
        snapshots.clear();
    }

}
