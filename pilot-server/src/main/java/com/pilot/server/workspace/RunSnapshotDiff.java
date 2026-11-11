package com.pilot.server.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pilot.core.multi.UnifiedDiff;
import com.pilot.server.workspace.ChangeService.RunChangeReport;
import com.pilot.server.workspace.ChangeService.RunDiffBudget;
import com.pilot.server.workspace.ChangeService.RunFileChange;
import com.pilot.server.workspace.RunSnapshotService.SnapshotEntry;
import com.pilot.server.workspace.RunSnapshotService.SnapshotMeta;

/** 用写前备份和完成态文件生成可持久保存的审阅差异。 */
final class RunSnapshotDiff {
    private static final Logger log = LoggerFactory.getLogger(RunSnapshotDiff.class);
    private static final String CHANGED_EXTERNALLY = "文件在本轮结束前被外部修改，无法确认逐行差异。";
    private static final String MISSING_BACKUP = "缺少可用的改前快照，无法显示逐行差异。";
    private static final String NON_TEXT = "文件是二进制或非 UTF-8 文本，无法显示逐行差异。";

    private RunSnapshotDiff() {
    }

    static RunChangeReport capture(SnapshotMeta meta, Path backups, Set<String> unavailable) {
        Path workspace = Path.of(meta.workspace());
        Set<String> conflicts = Set.copyOf(meta.conflicts());
        List<RunFileChange> files = new ArrayList<>();
        RunDiffBudget budget = new RunDiffBudget();
        int added = 0;
        int removed = 0;
        boolean omitted = false;
        for (SnapshotEntry entry : meta.files()) {
            boolean conflict = conflicts.contains(entry.path());
            boolean missing = unavailable.contains(entry.path());
            if (!conflict && !missing && unchanged(entry)) {
                continue;
            }
            if (files.size() >= ChangeService.MAX_FILES) {
                omitted = true;
                break;
            }
            RunFileChange change = captureFile(workspace, backups, entry, conflict, missing);
            added += change.added();
            removed += change.removed();
            files.add(new RunFileChange(change.path(), change.status(), change.added(), change.removed(),
                budget.limit(change.diff()), change.note()));
        }
        return new RunChangeReport(meta.runId(), meta.workspace(), ChangeService.hasGitMetadata(workspace),
            List.copyOf(files), added, removed, omitted || budget.truncated(), null, true);
    }

    private static boolean unchanged(SnapshotEntry entry) {
        if (!entry.existed()) {
            return entry.completedHash() == null;
        }
        return entry.originalHash() != null && entry.originalHash().equals(entry.completedHash());
    }

    private static RunFileChange captureFile(Path workspace, Path backups, SnapshotEntry entry, boolean conflict,
        boolean missing) {
        String path = entry.path();
        boolean present = entry.completedHash() != null;
        String status = !entry.existed() ? "A" : present || missing ? "M" : "D";
        if (conflict) {
            return unavailable(path, status, CHANGED_EXTERNALLY);
        }
        if (missing) {
            return unavailable(path, status, "无法读取本轮完成时的文件内容。");
        }
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            return unavailable(path, status, "文件名含换行，无法显示逐行差异。");
        }

        try {
            Path target = workspace.resolve(path).normalize();
            if (!RunSnapshotService.safePath(workspace, target)
                || present != Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return unavailable(path, status, CHANGED_EXTERNALLY);
            }
            String before = entry.existed()
                ? readText(backups, backups.resolve(path).normalize(), entry.originalHash(), MISSING_BACKUP) : null;
            String after = present ? readText(workspace, target, entry.completedHash(), CHANGED_EXTERNALLY) : null;
            if (before == null && "".equals(after)) {
                return unavailable(path, status, "已新建空文件。");
            }
            if (after == null && "".equals(before)) {
                return unavailable(path, status, "已删除空文件。");
            }
            String diff = present ? UnifiedDiff.create(path, before, after) : UnifiedDiff.deleteFile(path, before);
            if (diff.isEmpty()) {
                return unavailable(path, status, "文件内容有字节变化，标准化换行后的文本行相同。");
            }
            int[] stats = UnifiedDiff.countChanges(diff);
            return new RunFileChange(path, status, stats[0], stats[1], diff);
        } catch (PreviewUnavailable e) {
            return unavailable(path, status, e.getMessage());
        } catch (IOException e) {
            log.debug("读取本轮文件差异失败 {}", path, e);
            return unavailable(path, status, "无法读取本轮文件快照。");
        }
    }

    private static RunFileChange unavailable(String path, String status, String note) {
        return new RunFileChange(path, status, 0, 0, "", note);
    }

    private static String readText(Path root, Path path, String expectedHash, String changedMessage)
        throws IOException {
        if (!RunSnapshotService.safePath(root, path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PreviewUnavailable(changedMessage);
        }
        byte[] content;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            content = input.readNBytes(ChangeService.MAX_TEXT_PREVIEW_BYTES + 1);
        }
        if (content.length > ChangeService.MAX_TEXT_PREVIEW_BYTES) {
            throw new PreviewUnavailable("文件超过 " + ChangeService.MAX_TEXT_PREVIEW_BYTES / 1024 + " KiB 的文本预览上限。");
        }
        if (!Objects.equals(RunSnapshotService.hash(content), expectedHash)) {
            throw new PreviewUnavailable(changedMessage);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
            if (text.indexOf('\0') >= 0) {
                throw new PreviewUnavailable(NON_TEXT);
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new PreviewUnavailable(NON_TEXT, e);
        }
    }

    private static final class PreviewUnavailable extends IOException {
        PreviewUnavailable(String message) {
            super(message);
        }

        PreviewUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
