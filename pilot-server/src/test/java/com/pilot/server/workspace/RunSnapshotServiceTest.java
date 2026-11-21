package com.pilot.server.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.Message;
import com.pilot.core.tool.command.CommandChangeTracker;

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
    void recognizesOnlyChangesRestoredToTheActualRunBaseline() throws IOException {
        Path file = workspace.resolve("A.java");
        // 工作区本来就有未提交内容；基线必须用原始字节，不是 Git HEAD。
        Files.write(file, new byte[] {0, (byte)255, 10});
        snapshots.bind("run_restore", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "temporary");
        assertTrue(snapshots.restoredPaths().isEmpty());
        Files.write(file, new byte[] {0, (byte)255, 10});
        assertEquals(Set.of("A.java"), snapshots.restoredPaths());
        Files.delete(file);
        assertTrue(snapshots.restoredPaths().isEmpty(), "删除原文件不算还原");
        Path created = workspace.resolve("new.java");
        snapshots.beforeWrite(created);
        Files.writeString(created, "new");
        assertTrue(snapshots.restoredPaths().isEmpty());
        Files.delete(created);
        assertEquals(Set.of("new.java"), snapshots.restoredPaths());
        snapshots.clear();
        assertTrue(snapshots.restoredPaths().isEmpty(), "无本轮快照时不能排除任何改动");
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

        assertEquals(0, snapshots.purgeOlderThan(Instant.now(), Set.of("run_active")));
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
    void keepsTheWrittenLinesWhenTheFileIsEditedAgainBeforeTheRunFinishes() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "before");
        snapshots.bind("mid_run_manual", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "agent");
        snapshots.afterWrite(file);
        // 模型写完之后、本轮结束之前又有人动了这个文件：回滚必须拒绝，改动卡仍要说明这一轮写了什么
        Files.writeString(file, "manual while model thinks");
        snapshots.finish();
        snapshots.clear();
        assertEquals(List.of("a.txt"), snapshots.rollback("mid_run_manual").conflicts());
        assertEquals("manual while model thinks", Files.readString(file));
        ChangeService.RunFileChange change = snapshots.changes("mid_run_manual").files().getFirst();
        assertEquals(1, change.added());
        assertEquals(1, change.removed());
        assertTrue(change.countsKnown());
        assertTrue(change.diff().contains("-before"), change.diff());
        assertTrue(change.diff().contains("+agent"), change.diff());
        assertNull(change.note());
    }

    @Test
    void keepsRunLineCountsAfterTheFileChangesOnDiskLater() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "before\n");
        snapshots.bind("run_frozen", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "agent\n");
        snapshots.afterWrite(file);
        snapshots.finish();
        snapshots.clear();

        // 轮末之后手工改、甚至删掉文件，都不该把这一轮的数字变成 0
        Files.writeString(file, "manual afterwards\n");
        ChangeService.RunChangeReport report = snapshots.changes("run_frozen");

        assertEquals(1, report.added());
        assertEquals(1, report.removed());
        assertTrue(report.files().getFirst().countsKnown());
        Files.delete(file);
        assertEquals(1, snapshots.changes("run_frozen").added());
    }

    @Test
    void explainsWhySomeChangedFilesHaveNoTextPreview() throws IOException {
        Path binary = workspace.resolve("image.bin");
        Path large = workspace.resolve("large.txt");
        Path noBackup = workspace.resolve("missing.txt");
        Files.writeString(noBackup, "old\n");

        snapshots.bind("preview_limits", workspace);
        snapshots.beforeWrite(binary);
        Files.write(binary, new byte[] {1, 0, 2});
        snapshots.afterWrite(binary);
        snapshots.beforeWrite(large);
        Files.writeString(large, "a".repeat(ChangeService.MAX_TEXT_PREVIEW_BYTES + 1));
        snapshots.afterWrite(large);
        snapshots.beforeWrite(noBackup);
        Files.writeString(noBackup, "new\n");
        snapshots.afterWrite(noBackup);
        Files.delete(storage.resolve("preview_limits/files/missing.txt"));
        snapshots.finish();
        snapshots.clear();

        List<ChangeService.RunFileChange> files = snapshots.changes("preview_limits").files();
        assertEquals(List.of("image.bin", "large.txt", "missing.txt"),
            files.stream().map(ChangeService.RunFileChange::path).toList());
        assertTrue(files.stream().allMatch(file -> file.diff().isEmpty()));
        assertTrue(files.get(0).note().contains("二进制"));
        assertTrue(files.get(1).note().contains("预览上限"));
        assertTrue(files.get(2).note().contains("改前快照"));
        assertTrue(files.stream().noneMatch(ChangeService.RunFileChange::countsKnown), "算不出行数时必须是未知，不能报成 +0 -0");
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
        ChangeService.RunChangeReport diff = snapshots.changes("command_run");
        assertEquals(List.of("A", "M", "D"), diff.files().stream().map(ChangeService.RunFileChange::status).toList());
        assertEquals(2, diff.added());
        assertEquals(2, diff.removed());
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

    /** 单个文件被占用/无权限时只跳过它，其余文件照常进入基线，且结果里说明这些文件没被追踪。 */
    @Test
    void unreadableFileIsSkippedWithoutLosingTheRestOfTheBaseline() throws IOException {
        Path locked = workspace.resolve("data/app.db");
        Path tracked = workspace.resolve("src/App.java");
        Files.createDirectories(locked.getParent());
        Files.writeString(locked, "db-v1");
        Files.createDirectories(tracked.getParent());
        Files.writeString(tracked, "class App { }\n");
        RunSnapshotService lockedService = new RunSnapshotService(JsonMapper.builder().build(), storage) {
            @Override
            CommandFile recordCommandFile(Path file, BasicFileAttributes attributes, Path backupTarget,
                long[] backedUpBytes) throws IOException {
                if (file.getFileName().toString().equals("app.db")) {
                    throw new IOException("另一个程序已锁定文件的一部分，进程无法访问。");
                }
                return super.recordCommandFile(file, attributes, backupTarget, backedUpBytes);
            }
        };

        lockedService.bind("run_locked", workspace);
        CommandChangeTracker.Handle handle = lockedService.begin(workspace);
        Files.writeString(tracked, "class App { int value; }\n");
        Files.writeString(locked, "db-v2");
        CommandChangeTracker.Result result = lockedService.finish(handle);
        lockedService.finish();
        lockedService.clear();

        assertTrue(result.complete(), result.warning());
        assertEquals(List.of("src/App.java"), result.changedPaths());
        assertEquals(List.of("M"),
            lockedService.changes("run_locked").files().stream().map(ChangeService.RunFileChange::status).toList());
        assertTrue(result.warning().contains("data/app.db"), result.warning());
        assertTrue(result.warning().contains("没有进入命令前文件基线"), result.warning());
        assertTrue(result.warning().contains("不能据此认为它们没有改动"), result.warning());
        assertFalse(result.warning().contains("未能建立命令前文件基线"), result.warning());

        RunSnapshotService.RollbackReport rollback = lockedService.rollback("run_locked");
        assertTrue(rollback.failed().isEmpty(), rollback.failed().toString());
        assertEquals("class App { }\n", Files.readString(tracked));
    }

    @Test
    void comparesDegradedBaselineWithoutReportingUnchangedFiles() throws IOException {
        Path unchanged = Files.writeString(workspace.resolve("unchanged.txt"), "before");
        Path modified = Files.writeString(workspace.resolve("modified.txt"), "before");
        Path deleted = Files.writeString(workspace.resolve("deleted.txt"), "before");
        snapshots.bind("run_degraded", workspace);
        CommandChangeTracker.Handle handle = snapshots.begin(workspace, 0);

        Files.writeString(modified, "larger after");
        Files.delete(deleted);
        Files.writeString(workspace.resolve("created.txt"), "new");
        CommandChangeTracker.Result result = snapshots.finish(handle);
        snapshots.finish();
        snapshots.clear();

        assertTrue(result.complete(), result.warning());
        assertEquals(List.of("created.txt", "deleted.txt", "modified.txt"), result.changedPaths());
        assertTrue(result.warning().contains("3 个文件仅按大小和修改时间"), result.warning());
        assertEquals("before", Files.readString(unchanged));
        assertTrue(snapshots.rollback("run_degraded").failed().contains("modified.txt"), "降级基线没有原始字节，不能用于恢复已修改文件");
    }

    @Test
    void commandChangeTrackingFiltersGitIgnoredGeneratedOutputsWhileKeepingTrackedAndUnignoredFiles() throws Exception {
        git(workspace, "init");
        git(workspace, "config", "user.email", "test@example.com");
        git(workspace, "config", "user.name", "test");
        Files.writeString(workspace.resolve(".gitignore"), "generated/static/\n");
        Path trackedIgnored = Files.createDirectories(workspace.resolve("generated/static")).resolve("tracked.txt");
        Files.writeString(trackedIgnored, "v1");
        git(workspace, "add", ".gitignore");
        git(workspace, "add", "-f", "generated/static/tracked.txt");
        git(workspace, "commit", "-m", "init");

        snapshots.bind("run_gitignore", workspace);
        CommandChangeTracker.Handle handle = snapshots.begin(workspace);

        Files.writeString(workspace.resolve("generated/static/bundle.js"), "compiled asset");
        Files.writeString(trackedIgnored, "v2");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {}\n");

        CommandChangeTracker.Result result = snapshots.finish(handle);
        snapshots.finish();
        snapshots.clear();

        assertTrue(result.complete(), result.warning());
        assertEquals(List.of("generated/static/tracked.txt", "src/App.java"), result.changedPaths(),
            "Git 忽略的未跟踪构建产物应被过滤，已跟踪文件与普通新源码必须保留");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    private void change(String runId, Path file, String content) throws IOException {
        snapshots.bind(runId, workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, content);
        snapshots.finish();
        snapshots.clear();
    }

}
