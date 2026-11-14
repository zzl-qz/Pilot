package com.pilot.core.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pilot.core.workspace.PathValidator;

/**
 * 沙箱版 {@code edit_file}：语义与真实写工具完全一致（old_string 必须唯一命中），
 * 但结果只提交到变更沙箱，不写磁盘。
 *
 * <p>工具名保持不变，子 Agent 的行为与主 Agent 对齐；隔离靠「写不进去」而不是「提示词要求别写」。
 */
public final class SandboxEditFileTool extends PromptedTool {
    private final PathValidator paths;
    private final SandboxSink sandbox;
    private final ReadGuard reads;

    public SandboxEditFileTool(PathValidator paths, SandboxSink sandbox) {
        this(paths, sandbox, ReadGuard.allowAll());
    }

    public SandboxEditFileTool(PathValidator paths, SandboxSink sandbox, ReadGuard reads) {
        super(ToolNames.EDIT_FILE);
        this.paths = java.util.Objects.requireNonNull(paths, "paths must not be null");
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public Map<String, Object> parameters() {
        return EditFileTool.schema();
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
            file = paths.resolve(sandbox.canonicalPath(paths.display(file)));
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        String shown = paths.display(file);
        // 沙箱里新建的文件磁盘上还不存在，原文要从中提案取；否则「建完接着改」会被当成文件不存在
        String diskText = null;
        if (Files.isRegularFile(file)) {
            try {
                diskText = EditableText.readForReplacement(file, rawPath);
            } catch (IllegalArgumentException e) {
                return "ERROR: " + e.getMessage();
            } catch (IOException e) {
                return "ERROR: 读取失败: " + e.getMessage();
            }
        }
        String content = sandbox.currentText(shown, diskText);
        if (content == null) {
            return "ERROR: 文件不存在: " + rawPath;
        }
        // 先读后改是机制约束：没读过的文件拒绝编辑，不依赖提示词劝导
        if (!reads.isObserved(file)) {
            return "ERROR: 本次运行尚未读取过 " + rawPath + "，无法确认原文与行号。先 read_file 查看当前内容，再给出 old_string。";
        }
        try {
            ExactEditBatch.Applied applied;
            try {
                applied = ExactEditBatch.apply(content, edits);
            } catch (IllegalArgumentException e) {
                return "ERROR: " + e.getMessage();
            }
            sandbox.record(shown, content, applied.content());
            reads.observed(file);
            return "已提交到沙箱 " + shown + "：替换 " + applied.count() + " 处（" + applied.oldLines() + " 行 → "
                + applied.newLines() + " 行）";
        } catch (RuntimeException e) {
            return "ERROR: 提交沙箱失败: " + e.getMessage();
        }
    }

}
