package com.pilot.core.tool.file;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/** 普通和沙箱编辑共用的参数校验、模式定义、写前观察检查与审批预览。 */
abstract class EditFileOperation extends PromptedTool {
    final PathValidator paths;
    final ReadGuard reads;

    EditFileOperation(PathValidator paths, ReadGuard reads) {
        super(ToolNames.EDIT_FILE);
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public Map<String, Object> parameters() {
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
    public final String execute(Map<String, Object> args) {
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
            file = resolveTarget(rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        return edit(file, rawPath, edits);
    }

    Path resolveTarget(String rawPath) {
        return paths.resolveWritable(rawPath);
    }

    /** 校验「先读后改」约束；通过返回 {@code null}，否则返回可直接返回给模型的错误说明。 */
    String checkObserved(Path file, String rawPath, boolean checkFresh) {
        if (!reads.isObserved(file)) {
            return "ERROR: 本次运行尚未读取过 " + rawPath + "，无法确认原文与行号。先 read_file 查看当前内容，再给出 old_string。";
        }
        if (checkFresh && !reads.isFresh(file)) {
            return "ERROR: 文件 " + rawPath + " 自上次读取后已被外部修改，请先 read_file 查看最新内容后再编辑。";
        }
        return null;
    }

    @Override
    public Map<String, Object> approvalBinding(Map<String, Object> args) {
        return ApprovalDiffPreview.forEdit(args);
    }

    abstract String edit(Path file, String rawPath, List<ExactEditBatch.Edit> edits);
}
