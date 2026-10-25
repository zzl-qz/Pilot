package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 在工作区内创建新文件，父目录自动创建；已存在的文件不覆盖，要求模型改用 edit_file。 */
public final class CreateFileTool implements Tool {
    private final PathValidator paths;

    public CreateFileTool(PathValidator paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "create_file";
    }

    @Override
    public String description() {
        return "在工作区内创建一个新的文本文件，父目录会自动创建。文件已存在时会报错，请改用 edit_file。";
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
        if (file.getParent() == null) {
            return "ERROR: 非法路径: " + rawPath;
        }

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            return "已创建 " + paths.display(file) + "（" + bytes + " 字节，"
                    + (int) content.lines().count() + " 行）";
        } catch (IOException e) {
            return "ERROR: 创建失败: " + e.getMessage();
        }
    }
}
