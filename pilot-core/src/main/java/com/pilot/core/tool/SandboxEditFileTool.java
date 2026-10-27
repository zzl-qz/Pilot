package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 沙箱版 {@code edit_file}：语义与真实写工具完全一致（old_string 必须唯一命中），
 * 但结果只提交到变更沙箱，不写磁盘。
 *
 * <p>工具名保持不变，子 Agent 的行为与主 Agent 对齐；隔离靠「写不进去」而不是「提示词要求别写」。
 */
public final class SandboxEditFileTool implements Tool {
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private final PathValidator paths;
    private final SandboxSink sandbox;

    public SandboxEditFileTool(PathValidator paths, SandboxSink sandbox) {
        this.paths = java.util.Objects.requireNonNull(paths, "paths must not be null");
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox must not be null");
    }

    @Override
    public String name() {
        return "edit_file";
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
                        "old_string", Map.of("type", "string", "description", "要被替换的原文，必须唯一匹配"),
                        "new_string", Map.of("type", "string", "description", "替换后的内容，可为空字符串表示删除")),
                "required", List.of("path", "old_string", "new_string"),
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
        Object oldValue = args == null ? null : args.get("old_string");
        Object newValue = args == null ? null : args.get("new_string");
        if (oldValue == null || oldValue.toString().isEmpty()) {
            return "ERROR: 缺少参数 old_string（不能为空）";
        }
        if (newValue == null) {
            return "ERROR: 缺少参数 new_string";
        }
        String oldString = oldValue.toString();
        String newString = newValue.toString();

        Path file;
        try {
            file = paths.resolve(rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.isRegularFile(file)) {
            return "ERROR: 文件不存在: " + rawPath;
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                return "ERROR: 文件过大（上限 32MB），无法整份替换。";
            }
            byte[] bytes = Files.readAllBytes(file);
            if (ToolSupport.isBinary(bytes)) {
                return "ERROR: 二进制文件，无法编辑: " + rawPath;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String search = oldString;
            String replacement = newString;
            int index = content.indexOf(search);
            if (index < 0 && content.contains("\r\n")) {
                search = toCrlf(oldString);
                replacement = toCrlf(newString);
                index = content.indexOf(search);
            }
            if (index < 0) {
                return "ERROR: 未找到 old_string。请先用 read_file 确认原文与缩进。";
            }
            if (content.indexOf(search, index + search.length()) >= 0) {
                return "ERROR: old_string 匹配到多处，无法唯一定位。请提供更多上下文后重试。";
            }
            String updated = content.substring(0, index) + replacement + content.substring(index + search.length());
            sandbox.record(paths.display(file), content, updated);
            return "已提交到沙箱 " + paths.display(file) + "：替换 1 处（"
                    + (int) search.lines().count() + " 行 → " + (int) replacement.lines().count() + " 行）";
        } catch (IOException e) {
            return "ERROR: 读取失败: " + e.getMessage();
        } catch (RuntimeException e) {
            return "ERROR: 提交沙箱失败: " + e.getMessage();
        }
    }

    private static String toCrlf(String text) {
        return text.replace("\r\n", "\n").replace("\n", "\r\n");
    }
}
