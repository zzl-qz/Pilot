package com.pilot.core.tool.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
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
 * 跨平台移动或重命名工作区内的普通文件，自动创建目标父目录并写入运行快照以支持回滚。
 */
public final class MoveFileTool extends PromptedTool implements WritesFiles {
    private final PathValidator paths;
    private final SnapshotSink snapshots;
    private final ReadGuard reads;

    public MoveFileTool(PathValidator paths) {
        this(paths, SnapshotSink.none(), ReadGuard.allowAll());
    }

    public MoveFileTool(PathValidator paths, SnapshotSink snapshots, ReadGuard reads) {
        super(ToolNames.MOVE_FILE);
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("source", ToolSchemas.string("工作区内源文件的相对路径（仅支持普通文件）"));
        props.put("destination", ToolSchemas.string("工作区内目标文件的相对路径，不存在的父目录会自动创建"));
        props.put("overwrite", Map.of("type", "boolean", "description", "可选：目标文件已存在时是否允许覆盖，默认 false"));
        return ToolSchemas.object(props, List.of("source", "destination"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String rawSource = ToolSupport.stringArg(args, "source");
        String rawDest = ToolSupport.stringArg(args, "destination");
        if (rawSource == null || rawSource.isBlank()) {
            return ToolResult.text("ERROR: 缺少参数 source");
        }
        if (rawDest == null || rawDest.isBlank()) {
            return ToolResult.text("ERROR: 缺少参数 destination");
        }
        boolean overwrite = args != null && Boolean.TRUE.equals(args.get("overwrite"));
        Path source;
        Path dest;
        try {
            source = paths.resolveWritable(rawSource);
            dest = paths.resolveWritable(rawDest);
        } catch (RuntimeException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        }
        if (source.equals(dest)) {
            return ToolResult.text("ERROR: 源路径与目标路径相同: " + rawSource);
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.text("ERROR: 源文件不存在: " + rawSource);
        }
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.text("ERROR: 仅支持移动普通文件，不支持目录或符号链接: " + rawSource);
        }
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            if (!overwrite) {
                return ToolResult.text("ERROR: 目标文件已存在（如需覆盖请显式传 overwrite=true）: " + rawDest);
            }
            if (Files.isSymbolicLink(dest) || !Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.text("ERROR: 目标不是普通文件，无法覆盖: " + rawDest);
            }
        }
        if (dest.getParent() == null) {
            return ToolResult.text("ERROR: 非法目标路径: " + rawDest);
        }
        String srcShown = paths.display(source);
        String destShown = paths.display(dest);
        try {
            Files.createDirectories(dest.getParent());
            snapshots.beforeWrite(source);
            snapshots.beforeWrite(dest);
            if (overwrite) {
                Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, dest);
            }
            snapshots.afterWrite(dest);
            reads.observed(dest);
            reads.noteWrite();
            return new ToolResult("已移动 " + srcShown + " → " + destShown, List.of(srcShown, destShown), List.of());
        } catch (IOException e) {
            return ToolResult.text("ERROR: 移动失败: " + e.getMessage());
        }
    }

    @Override
    public String changedPath(ToolCall call) {
        return null;
    }
}
