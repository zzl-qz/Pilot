package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 沙箱版 {@code create_file}：新文件同样只进沙箱，等用户接受变更后才真正创建。
 *
 * <p>已存在的文件仍然拒绝创建，提示改用 {@code edit_file}，与真实写工具保持一致。
 */
public final class SandboxCreateFileTool implements Tool {
    private final PathValidator paths;
    private final SandboxSink sandbox;
    private final ReadGuard reads;

    public SandboxCreateFileTool(PathValidator paths, SandboxSink sandbox) {
        this(paths, sandbox, ReadGuard.allowAll());
    }

    public SandboxCreateFileTool(PathValidator paths, SandboxSink sandbox, ReadGuard reads) {
        this.paths = java.util.Objects.requireNonNull(paths, "paths must not be null");
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public String name() {
        return "create_file";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "工作区内的相对文件路径"),
                        "content", Map.of("type", "string", "description", "文件的完整内容")),
                "required", List.of("path", "content"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return false;
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
        try {
            sandbox.record(paths.display(file), null, content);
            // 刚创建的文件视为已读：后续 edit_file 不会被「先读后改」拦截
            reads.observed(file);
            return "已提交到沙箱 " + paths.display(file) + "（新文件，"
                    + (int) content.lines().count() + " 行）";
        } catch (RuntimeException e) {
            return "ERROR: 提交沙箱失败: " + e.getMessage();
        }
    }
}
