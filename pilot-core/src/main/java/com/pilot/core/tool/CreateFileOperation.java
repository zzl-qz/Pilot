package com.pilot.core.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.workspace.PathValidator;

/** 普通和沙箱创建共用的参数校验与目标文件检查。 */
abstract class CreateFileOperation extends PromptedTool {
    final PathValidator paths;
    final ReadGuard reads;

    CreateFileOperation(PathValidator paths, ReadGuard reads) {
        super("create_file");
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
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
    public final String execute(Map<String, Object> args) {
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
        if (Files.exists(file) || existsInSandbox(file)) {
            return "ERROR: 文件已存在: " + rawPath + "，请改用 edit_file 修改。";
        }
        return create(file, rawPath, content);
    }

    boolean existsInSandbox(Path file) {
        return false;
    }

    abstract String create(Path file, String rawPath, String content);
}
