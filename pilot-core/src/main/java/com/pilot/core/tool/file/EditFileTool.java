package com.pilot.core.tool.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import com.pilot.core.message.ToolCall;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.tool.WritesFiles;
import com.pilot.core.workspace.PathValidator;

/**
 * 精确文本替换。old_string 必须唯一命中，否则返回结构化错误让模型补充上下文后重试。
 * 这是 Coding Agent 与普通文本替换的关键区别：绝不自动选择第一处匹配。
 */
public final class EditFileTool extends EditFileOperation implements WritesFiles {
    private final SnapshotSink snapshots;

    public EditFileTool(PathValidator paths) {
        this(paths, SnapshotSink.none());
    }

    public EditFileTool(PathValidator paths, SnapshotSink snapshots) {
        this(paths, snapshots, ReadGuard.allowAll());
    }

    public EditFileTool(PathValidator paths, SnapshotSink snapshots, ReadGuard reads) {
        super(paths, reads);
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
    }

    @Override
    String edit(Path file, String rawPath, List<ExactEditBatch.Edit> edits) {
        if (!Files.exists(file)) {
            return "ERROR: 文件不存在: " + rawPath;
        }
        if (!Files.isRegularFile(file)) {
            return "ERROR: 不是普通文件: " + rawPath;
        }
        // 先读后改是机制约束：本次运行没读过的文件拒绝编辑，不依赖提示词劝导。
        // 刚创建或刚编辑过的文件已在 ReadGuard 登记过，连续编辑不受影响。
        String guardError = checkObserved(file, rawPath, true);
        if (guardError != null) {
            return guardError;
        }
        try {
            String content;
            ExactEditBatch.Applied applied;
            try {
                content = EditableText.readForReplacement(file, rawPath);
                applied = ExactEditBatch.apply(content, edits);
            } catch (IllegalArgumentException e) {
                reads.invalidateDeliveredView(file);
                return "ERROR: " + e.getMessage();
            }
            snapshots.beforeWrite(file);
            Files.writeString(file, applied.content(), StandardCharsets.UTF_8);
            snapshots.afterWrite(file);
            reads.observed(file);
            reads.noteWrite();
            String note = applied.tolerantMatches() > 0 ? "，含 " + applied.tolerantMatches() + " 处缩进/行尾空白自动对齐" : "";
            String rangeNote = applied.lineRanges().isEmpty() ? "" : "，行段 " + String.join(", ", applied.lineRanges());
            return "已修改 " + paths.display(file) + "：替换 " + applied.count() + " 处（" + applied.oldLines() + " 行 → "
                + applied.newLines() + " 行" + rangeNote + note + "）";
        } catch (IOException e) {
            return "ERROR: 写入失败: " + e.getMessage();
        }
    }

    @Override
    public String changedPath(ToolCall call) {
        return ToolSupport.changedWorkspacePath(paths, call);
    }
}
