package com.pilot.core.tool.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolNames;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.tool.WritesFiles;
import com.pilot.core.workspace.PathValidator;

/**
 * 精确文本替换。old_string 必须唯一命中，否则返回结构化错误让模型补充上下文后重试。
 * 这是 Coding Agent 与普通文本替换的关键区别：绝不自动选择第一处匹配。
 */
public final class EditFileTool extends PromptedTool implements WritesFiles {
    private final PathValidator paths;
    private final SnapshotSink snapshots;
    private final ReadGuard reads;

    public EditFileTool(PathValidator paths) {
        this(paths, SnapshotSink.none());
    }

    public EditFileTool(PathValidator paths, SnapshotSink snapshots) {
        this(paths, snapshots, ReadGuard.allowAll());
    }

    public EditFileTool(PathValidator paths, SnapshotSink snapshots, ReadGuard reads) {
        super(ToolNames.EDIT_FILE);
        this.paths = java.util.Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = java.util.Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public Map<String, Object> parameters() {
        return schema();
    }

    static Map<String, Object> schema() {
        return ToolSchemas
            .object(
                Map.of("path", ToolSchemas.string("工作区内的相对文件路径"), "old_string",
                    ToolSchemas.string("单处替换的原文，必须唯一匹配；与 edits 二选一"), "new_string",
                    ToolSchemas.string("单处替换的新内容，可为空；与 edits 二选一"), "edits", Map
                        .of("type", "array", "description", "同一文件内多处互不重叠的替换；均匹配原文件，整体成功或整体失败", "items",
                            ToolSchemas.object(Map.of("old_string", ToolSchemas.string("唯一匹配的原文"), "new_string",
                                ToolSchemas.string("替换后的内容，可为空")), List.of("old_string", "new_string")))),
                List.of("path"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        if (rawPath == null) {
            return "ERROR: 缺少参数 path";
        }
        List<ExactEditBatch.Edit> edits;
        try {
            edits = ExactEditBatch.parse(args);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }

        Path file;
        try {
            file = paths.resolve(rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.exists(file)) {
            return "ERROR: 文件不存在: " + rawPath;
        }
        if (!Files.isRegularFile(file)) {
            return "ERROR: 不是普通文件: " + rawPath;
        }
        // 先读后改是机制约束：本次运行没读过的文件拒绝编辑，不依赖提示词劝导。
        // 刚创建或刚编辑过的文件已在 ReadGuard 登记过，连续编辑不受影响。
        if (!reads.isObserved(file)) {
            return "ERROR: 本次运行尚未读取过 " + rawPath + "，无法确认原文与行号。先 read_file 查看当前内容，再给出 old_string。";
        }

        try {
            ExactEditBatch.Applied applied;
            try {
                String content = EditableText.readForReplacement(file, rawPath);
                applied = ExactEditBatch.apply(content, edits);
            } catch (IllegalArgumentException e) {
                return "ERROR: " + e.getMessage();
            }
            snapshots.beforeWrite(file);
            Files.writeString(file, applied.content(), StandardCharsets.UTF_8);
            snapshots.afterWrite(file);
            reads.observed(file);
            return "已修改 " + paths.display(file) + "：替换 " + applied.count() + " 处（" + applied.oldLines() + " 行 → "
                + applied.newLines() + " 行）";
        } catch (IOException e) {
            return "ERROR: 写入失败: " + e.getMessage();
        }
    }

    @Override
    public String changedPath(ToolCall call) {
        return ToolSupport.changedWorkspacePath(paths, call);
    }

}
