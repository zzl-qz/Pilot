package com.pilot.server.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.loop.Message;
import com.pilot.core.tool.CommandChangeTracker;

import tools.jackson.databind.json.JsonMapper;

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
    void retentionKeepsSnapshotsForResumableRuns() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "original");
        snapshots.bind("run_active", workspace);
        snapshots.beforeWrite(file);
        snapshots.finish();
        snapshots.clear();
        Path snapshot = storage.resolve("run_active");
        Files.setLastModifiedTime(snapshot, FileTime.from(Instant.EPOCH));

        assertEquals(0, snapshots.purgeOlderThan(Instant.now(), java.util.Set.of("run_active")));
        assertTrue(Files.exists(snapshot));
        assertEquals(1, snapshots.purgeOlderThan(Instant.now()));
        assertFalse(Files.exists(snapshot));
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
        assertEquals(List.of("a.txt", "dir/new.txt"),
            meta.files().stream().map(RunSnapshotService.SnapshotEntry::path).toList());
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
        RunSnapshotService.RollbackReport blocked = snapshots.rollback(List.of("chain_1", "chain_2"));
        assertEquals(List.of("later.txt"), blocked.conflicts());
        assertEquals("second", Files.readString(file), "任何冲突都应在写文件前拦截");
        Files.writeString(later, "new");
        RunSnapshotService.RollbackReport restored = snapshots.rollback(List.of("chain_1", "chain_2"));
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
    void refusesSymlinkedParentBackup() throws IOException {
        Path file = workspace.resolve("dir/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "before");
        change("linked_parent", file, "after");
        Path moved = workspace.resolve("moved");
        Files.move(file.getParent(), moved);
        try {
            Files.createSymbolicLink(file.getParent(), moved);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 创建符号链接需要 SeCreateSymbolicLinkPrivilege（管理员或开发者模式）。
            // 无特权时跳过而非报错：被测行为依赖符号链接真实存在，环境给不了就换机器再验。
            Assumptions.assumeTrue(false, "本机无法创建符号链接（需要管理员或开发者模式），跳过符号链接拒绝断言");
            return;
        }
        assertEquals(List.of("dir/a.txt"), snapshots.rollback("linked_parent").conflicts());
        assertEquals("after", Files.readString(moved.resolve("a.txt")));
    }

    @Test
    void refusesDirectoryReplacingNewFile() throws IOException {
        Path created = workspace.resolve("new.txt");
        change("directory_collision", created, "new");
        Files.delete(created);
        Files.createDirectory(created);
        assertEquals(List.of("new.txt"), snapshots.rollback("directory_collision").conflicts());
    }

    @Test
    void conversationCheckpointSurvivesServiceRestartWithExactMessages() {
        List<Message> messages = List.of(Message.user("重复"), Message.assistant("之前的回复", List.of()), Message.user("重复"));
        snapshots.saveConversation("conversation", messages, List.of());
        RunSnapshotService reopened = new RunSnapshotService(JsonMapper.builder().build(), storage);
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
        Files.writeString(directory.resolve("meta.json"), "{\"runId\":\"legacy\",\"workspace\":\"/tmp\",\"files\":[]}");
        assertFalse(snapshots.preview(List.of("legacy")).ready());
    }

    @Test
    void importsOnlySourceChangesMadeByACommandAndRollsThemBack() throws IOException {
        Path changed = workspace.resolve("src/A.java");
        Path deleted = workspace.resolve("src/Delete.java");
        Path created = workspace.resolve("generated/B.java");
        Files.createDirectories(changed.getParent());
        Files.writeString(changed, "class A { }\n");
        Files.writeString(deleted, "class Delete { }\n");

        snapshots.bind("command_run", workspace);
        CommandChangeTracker.Handle handle = snapshots.begin(workspace);
        Files.writeString(changed, "class A { int value; }\n");
        Files.delete(deleted);
        Files.createDirectories(created.getParent());
        Files.writeString(created, "class B { }\n");
        Path buildNoise = workspace.resolve("target/generated.bin");
        Files.createDirectories(buildNoise.getParent());
        Files.writeString(buildNoise, "artifact");

        CommandChangeTracker.Result changes = snapshots.finish(handle);
        snapshots.finish();
        snapshots.clear();

        assertTrue(changes.complete(), changes.warning());
        assertEquals(List.of("generated/B.java", "src/A.java", "src/Delete.java"), changes.changedPaths());
        assertFalse(changes.changedPaths().contains("target/generated.bin"));
        RunSnapshotService.RollbackReport rollback = snapshots.rollback("command_run");
        assertTrue(rollback.failed().isEmpty(), rollback.failed().toString());
        assertEquals("class A { }\n", Files.readString(changed));
        assertEquals("class Delete { }\n", Files.readString(deleted));
        assertFalse(Files.exists(created));
        assertTrue(Files.exists(buildNoise), "ignored build artifacts are not part of source rollback");
    }

    @Test
    void commandBaselineCanFinishInALaterRun() throws IOException {
        Path file = workspace.resolve("src/later.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "before");
        snapshots.bind("launch_run", workspace);
        CommandChangeTracker.Handle handle = snapshots.begin(workspace);
        snapshots.finish();
        snapshots.clear();

        Files.writeString(file, "background change");
        snapshots.bind("status_run", workspace);
        CommandChangeTracker.Result result = snapshots.finish(handle);
        snapshots.finish();
        snapshots.clear();

        assertEquals(List.of("src/later.txt"), result.changedPaths());
        assertEquals("background change", Files.readString(file));
        RunSnapshotService.RollbackReport rollback = snapshots.rollback("status_run");
        assertTrue(rollback.failed().isEmpty());
        assertEquals("before", Files.readString(file));
    }

    private void change(String runId, Path file, String content) throws IOException {
        snapshots.bind(runId, workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, content);
        snapshots.finish();
        snapshots.clear();
    }

}
