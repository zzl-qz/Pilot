package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 精确文本替换。old_string 必须唯一命中，否则返回结构化错误让模型补充上下文后重试。
 * 这是 Coding Agent 与普通文本替换的关键区别：绝不自动选择第一处匹配。
 */
public final class EditFileTool implements Tool, WritesFiles {
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private final PathValidator paths;
    private final SnapshotSink snapshots;

    public EditFileTool(PathValidator paths) {
        this(paths, SnapshotSink.none());
    }

    public EditFileTool(PathValidator paths, SnapshotSink snapshots) {
        this.paths = java.util.Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = java.util.Objects.requireNonNull(snapshots, "snapshots must not be null");
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "把工作区内文本文件中的 old_string 精确替换为 new_string。"
                + "old_string 必须在文件中唯一出现；匹配到多处会报错，需要提供更多上下文。"
                + "较大的文本文件也可以改，上限 32MB。";
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
        String oldString = raw(args, "old_string");
        String newString = raw(args, "new_string");
        if (oldString == null || oldString.isEmpty()) {
            return "ERROR: 缺少参数 old_string（不能为空）";
        }
        if (newString == null) {
            return "ERROR: 缺少参数 new_string";
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
                return "ERROR: 未找到 old_string。最接近的文件片段：\n" + closestExcerpt(content, oldString)
                        + "\n请基于该片段提供更精确的旧内容，不要猜测缩进。";
            }

            int next = content.indexOf(search, index + search.length());
            if (next >= 0) {
                int count = countMatches(content, search);
                return "ERROR: old_string 匹配到 " + count + " 处，无法唯一定位。请提供更多上下文后重试。";
            }

            String updated = content.substring(0, index) + replacement + content.substring(index + search.length());
            snapshots.beforeWrite(file);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return "已修改 " + paths.display(file) + "：替换 1 处（"
                    + lineCount(search) + " 行 → " + lineCount(replacement) + " 行）";
        } catch (IOException e) {
            return "ERROR: 写入失败: " + e.getMessage();
        }
    }

    @Override
    public String changedPath(ToolCall call) {
        String rawPath = ToolSupport.stringArg(call == null ? null : call.args(), "path");
        if (rawPath == null) {
            return null;
        }
        try {
            return paths.display(paths.resolve(rawPath));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String raw(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : value.toString();
    }

    private static String toCrlf(String text) {
        return text.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private static int countMatches(String content, String search) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = content.indexOf(search, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + search.length();
        }
    }

    private static String closestExcerpt(String content, String search) {
        String[] sourceLines = content.split("\\R", -1);
        String[] searchLines = search.split("\\R", -1);
        String needle = searchLines.length == 0 ? search : searchLines[0].strip();
        int bestLine = 0;
        int bestScore = -1;
        for (int i = 0; i < sourceLines.length; i++) {
            String candidate = sourceLines[i].strip();
            int score = commonPrefix(candidate, needle);
            if (!needle.isEmpty() && candidate.contains(needle)) score += needle.length();
            if (score > bestScore) {
                bestScore = score;
                bestLine = i;
            }
        }
        int from = Math.max(0, bestLine - 2);
        int to = Math.min(sourceLines.length, bestLine + 3);
        StringBuilder excerpt = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (excerpt.length() > 0) excerpt.append('\n');
            excerpt.append(i + 1).append(": ").append(sourceLines[i]);
        }
        return excerpt.toString();
    }

    private static int commonPrefix(String left, String right) {
        int length = Math.min(left.length(), right.length());
        int index = 0;
        while (index < length && left.charAt(index) == right.charAt(index)) index++;
        return index;
    }

    private static int lineCount(String text) {
        return (int) text.lines().count();
    }
}
