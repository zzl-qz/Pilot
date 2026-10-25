package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 在工作区内按正则搜索文本，返回 {@code 路径:行号: 内容}。
 * 跳过构建产物、依赖目录与二进制文件，结果数量有上限。
 */
public final class SearchTool implements Tool {
    private static final int MAX_MATCHES = 200;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_LINE_CHARS = 300;
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".idea", ".gradle", "target", "build", "node_modules", "out", "dist");

    private final PathValidator paths;

    public SearchTool(PathValidator paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "search_text";
    }

    @Override
    public String description() {
        return "在工作区文件中按正则表达式搜索文本，返回匹配的 路径:行号: 内容。pattern 为正则，path 省略表示搜索整个工作区。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Java 正则表达式"),
                        "path", Map.of("type", "string", "description", "工作区内的相对路径，省略表示根目录")),
                "required", List.of("pattern"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String patternText = ToolSupport.stringArg(args, "pattern");
        if (patternText == null) {
            return "ERROR: 缺少参数 pattern";
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            return "ERROR: 正则表达式非法: " + e.getDescription();
        }
        String rawPath = ToolSupport.stringArg(args, "path");
        Path base;
        try {
            base = paths.resolve(rawPath == null ? "." : rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.exists(base)) {
            return "ERROR: 路径不存在: " + (rawPath == null ? "." : rawPath);
        }

        List<String> matches = new ArrayList<>();
        boolean truncated = false;
        for (Path file : candidateFiles(base)) {
            if (matches.size() >= MAX_MATCHES) {
                truncated = true;
                break;
            }
            if (searchFile(file, pattern, matches)) {
                truncated = true;
                break;
            }
        }
        if (matches.isEmpty()) {
            return "未找到匹配项: " + patternText;
        }
        String body = String.join("\n", matches);
        return truncated ? body + "\n… 结果已截断（上限 " + MAX_MATCHES + " 条）" : body;
    }

    private List<Path> candidateFiles(Path base) {
        if (Files.isRegularFile(base)) {
            return List.of(base);
        }
        try (var stream = Files.walk(base)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !inSkippedDirectory(base, path))
                    .sorted(Comparator.comparing(paths::display))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean inSkippedDirectory(Path base, Path file) {
        Path relative = base.relativize(file);
        for (Path segment : relative) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /** @return 是否因达到上限而提前结束 */
    private boolean searchFile(Path file, Pattern pattern, List<String> matches) {
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return false;
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return false;
        }
        if (ToolSupport.isBinary(bytes)) {
            return false;
        }
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        String display = paths.display(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!pattern.matcher(line).find()) {
                continue;
            }
            String compact = line.strip();
            if (compact.length() > MAX_LINE_CHARS) {
                compact = compact.substring(0, MAX_LINE_CHARS) + "…";
            }
            matches.add(display + ":" + (i + 1) + ": " + compact);
            if (matches.size() >= MAX_MATCHES) {
                return true;
            }
        }
        return false;
    }
}
