package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 读取工作区内的文本文件，返回带行号的内容。 */
public final class ReadFileTool implements Tool {
    private static final long MAX_BYTES = 512 * 1024;
    private static final int MAX_LINES = 2000;

    private final PathValidator paths;

    public ReadFileTool(PathValidator paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取工作区内一个文本文件的全部内容，返回带行号的内容。路径必须是工作区内的相对路径。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "工作区内的相对文件路径")),
                "required", List.of("path"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        if (rawPath == null) {
            return "ERROR: 缺少参数 path";
        }
        Path file;
        try {
            file = paths.resolve(rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.exists(file)) {
            return "ERROR: 文件不存在: " + rawPath;
        }
        if (!Files.isRegularFile(file)) {
            return "ERROR: 不是普通文件: " + rawPath;
        }
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                return "ERROR: 文件过大（" + size + " 字节，上限 " + MAX_BYTES + " 字节），请改用 search_text 定位片段。";
            }
            byte[] bytes = Files.readAllBytes(file);
            if (ToolSupport.isBinary(bytes)) {
                return "ERROR: 二进制文件，无法读取: " + rawPath;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                return "(空文件)";
            }
            List<String> lines = text.lines().toList();
            int limit = Math.min(lines.size(), MAX_LINES);
            int width = String.valueOf(limit).length();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                out.append(String.format(Locale.ROOT, "%" + width + "d | %s", i + 1, lines.get(i)));
                if (i < limit - 1) {
                    out.append('\n');
                }
            }
            if (lines.size() > limit) {
                out.append("\n… 已截断（共 ").append(lines.size()).append(" 行，仅显示前 ").append(limit).append(" 行）");
            }
            return out.toString();
        } catch (IOException e) {
            return "ERROR: 读取失败: " + e.getMessage();
        }
    }
}
