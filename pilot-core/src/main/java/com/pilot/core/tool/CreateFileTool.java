package com.pilot.core.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import com.pilot.core.workspace.PathValidator;

/** 在工作区内创建新文件，父目录自动创建；已存在的文件不覆盖，要求模型改用 edit_file。 */
public final class CreateFileTool extends PromptedTool implements WritesFiles {
    private final PathValidator paths;
    private final SnapshotSink snapshots;
    private final ReadGuard reads;

    public CreateFileTool(PathValidator paths) {
        this(paths, SnapshotSink.none());
    }

    public CreateFileTool(PathValidator paths, SnapshotSink snapshots) {
        this(paths, snapshots, ReadGuard.allowAll());
    }

    public CreateFileTool(PathValidator paths, SnapshotSink snapshots, ReadGuard reads) {
        super("create_file");
        this.paths = java.util.Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = java.util.Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public Map<String, Object> parameters() {
        return objectSchema(
            Map.of("path", Map.of("type", "string", "description", "工作区内的相对文件路径；文件不能已存在，改已有文件请用 edit_file"), "content",
                Map.of("type", "string", "description", "文件的完整内容")),
            List.of("path", "content"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        if (rawPath == null) {
            return "ERROR: 缺少参数 path";
        }
        Object contentValue = args == null ? null : args.get("content");
        if (contentValue == null) {
            return "ERROR: 缺少参数 content";
        }
        String content = contentValue.toString();

        Path file;
        try {
            file = paths.resolve(rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (Files.exists(file)) {
            return "ERROR: 文件已存在: " + rawPath + "，请改用 edit_file 修改。";
        }
        if (file.getParent() == null) {
            return "ERROR: 非法路径: " + rawPath;
        }

        try {
            Files.createDirectories(file.getParent());
            snapshots.beforeWrite(file);
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
            snapshots.afterWrite(file);
            // 刚创建的文件视为已读：后续 edit_file 不会被「先读后改」拦截
            reads.observed(file);
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            return "已创建 " + paths.display(file) + "（" + bytes + " 字节，" + (int)content.lines().count() + " 行）";
        } catch (IOException e) {
            return "ERROR: 创建失败: " + e.getMessage();
        }
    }

    @Override
    public String changedPath(ToolCall call) {
        return ToolSupport.changedWorkspacePath(paths, call);
    }
}
