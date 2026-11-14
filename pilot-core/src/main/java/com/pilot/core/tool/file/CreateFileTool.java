package com.pilot.core.tool.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.pilot.core.message.ToolCall;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.tool.WritesFiles;
import com.pilot.core.workspace.PathValidator;

/** 在工作区内创建新文件，父目录自动创建；已存在的文件不覆盖，要求模型改用 edit_file。 */
public final class CreateFileTool extends CreateFileOperation implements WritesFiles {
    private final SnapshotSink snapshots;

    public CreateFileTool(PathValidator paths) {
        this(paths, SnapshotSink.none());
    }

    public CreateFileTool(PathValidator paths, SnapshotSink snapshots) {
        this(paths, snapshots, ReadGuard.allowAll());
    }

    public CreateFileTool(PathValidator paths, SnapshotSink snapshots, ReadGuard reads) {
        super(paths, reads);
        this.snapshots = java.util.Objects.requireNonNull(snapshots, "snapshots must not be null");
    }

    @Override
    String create(Path file, String rawPath, String content) {
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
