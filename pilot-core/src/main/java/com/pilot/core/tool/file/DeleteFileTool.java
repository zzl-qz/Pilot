package com.pilot.core.tool.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.tool.WritesFiles;
import com.pilot.core.workspace.PathValidator;

/**
 * 删除工作区内的单个普通文件，并在删除前写入运行快照以便一键回滚。
 * 拒绝目录、符号链接、越界路径以及本次运行未读过的文件。
 */
public final class DeleteFileTool extends PromptedTool implements WritesFiles {
    private final PathValidator paths;
    private final SnapshotSink snapshots;
    private final ReadGuard reads;

    public DeleteFileTool(PathValidator paths) {
        this(paths, SnapshotSink.none(), ReadGuard.allowAll());
    }

    public DeleteFileTool(PathValidator paths, SnapshotSink snapshots, ReadGuard reads) {
        super(ToolNames.DELETE_FILE);
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("path", ToolSchemas.string("工作区内待删除的相对文件路径（仅支持普通文件）")), List.of("path"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        if (rawPath == null || rawPath.isBlank()) {
            return ToolResult.text("ERROR: 缺少参数 path");
        }
        Path file;
        try {
            file = paths.resolveWritableLeaf(rawPath);
        } catch (RuntimeException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.text("ERROR: 文件不存在: " + rawPath);
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.text("ERROR: 仅支持删除普通文件，不支持删除目录或符号链接: " + rawPath);
        }
        if (!reads.isObserved(file)) {
            return ToolResult.text("ERROR: 本次运行尚未读取过 " + rawPath + "，请先 read_file 确认内容后再删除。");
        }
        if (!reads.isFresh(file)) {
            return ToolResult.text("ERROR: 文件 " + rawPath + " 自上次读取后已被外部修改，请先 read_file 确认最新内容后再删除。");
        }
        String shown = paths.display(file);
        try {
            snapshots.beforeWrite(file);
            Files.delete(file);
            snapshots.afterWrite(file);
            reads.noteWrite();
            return new ToolResult("已删除 " + shown, List.of(shown), List.of());
        } catch (IOException e) {
            return ToolResult.text("ERROR: 删除失败: " + e.getMessage());
        }
    }

    @Override
    public String changedPath(ToolCall call) {
        return ToolSupport.changedWorkspacePath(paths, call);
    }
}
