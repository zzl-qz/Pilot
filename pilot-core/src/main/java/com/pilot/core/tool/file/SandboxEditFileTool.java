package com.pilot.core.tool.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.workspace.PathValidator;

/**
 * 沙箱版 {@code edit_file}：语义与真实写工具完全一致（old_string 必须唯一命中），
 * 但结果只提交到变更沙箱，不写磁盘。
 *
 * <p>工具名保持不变，子 Agent 的行为与主 Agent 对齐；隔离靠「写不进去」而不是「提示词要求别写」。
 */
public final class SandboxEditFileTool extends EditFileOperation {
    private final SandboxSink sandbox;

    public SandboxEditFileTool(PathValidator paths, SandboxSink sandbox) {
        this(paths, sandbox, ReadGuard.allowAll());
    }

    public SandboxEditFileTool(PathValidator paths, SandboxSink sandbox, ReadGuard reads) {
        super(paths, reads);
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox must not be null");
    }

    @Override
    Path resolveTarget(String rawPath) {
        Path resolved = paths.resolveWritable(rawPath);
        return paths.resolveWritable(sandbox.canonicalPath(paths.display(resolved)));
    }

    @Override
    String edit(Path file, String rawPath, List<ExactEditBatch.Edit> edits) {
        String shown = paths.display(file);
        // 沙箱里新建的文件磁盘上还不存在，原文要从提案取；否则「建完接着改」会被当成文件不存在
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
        String guardError = checkObserved(file, rawPath, false);
        if (guardError != null) {
            return guardError;
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
            reads.noteWrite();
            return "已提交到沙箱 " + shown + "：替换 " + applied.count() + " 处（" + applied.oldLines() + " 行 → "
                + applied.newLines() + " 行）";
        } catch (RuntimeException e) {
            return "ERROR: 提交沙箱失败: " + e.getMessage();
        }
    }
}
