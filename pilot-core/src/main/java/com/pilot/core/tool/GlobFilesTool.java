package com.pilot.core.tool;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pilot.core.workspace.PathValidator;

/**
 * 按 glob 在整个子树里找文件，一次调用替代逐层 list_dir。
 * 跳过依赖与构建产物目录；结果按修改时间从新到旧排列，最近改过的文件最先出现。
 */
public final class GlobFilesTool implements Tool.ParallelReadOnly {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_VISITED = 200_000;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".idea", ".gradle", ".pilot", ".venv",
        "__pycache__", "target", "build", "node_modules", "out", "dist");

    private final PathValidator paths;

    public GlobFilesTool(PathValidator paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "glob_files";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("pattern",
                Map.of("type", "string", "description", "glob，相对 path；例如 **/*.java、src/**/*Test.java、docs/*.md"),
                "path", Map.of("type", "string", "description", "起始目录，工作区内的相对路径，默认根目录"), "limit",
                Map.of("type", "integer", "description", "最多返回条数，默认 " + DEFAULT_LIMIT + "，最大 " + MAX_LIMIT)),
            "required", List.of("pattern"), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pattern = ToolSupport.stringArg(args, "pattern");
        if (pattern == null) {
            return "ERROR: 缺少参数 pattern";
        }
        String normalized = pattern.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/") || normalized.contains("../")) {
            return "ERROR: pattern 必须是相对 path 的 glob，不能是绝对路径或包含 ..；用 path 指定起始目录";
        }
        int limit = DEFAULT_LIMIT;
        Object limitValue = args.get("limit");
        if (limitValue instanceof Number number) {
            limit = number.intValue();
        } else if (limitValue != null) {
            try {
                limit = Integer.parseInt(limitValue.toString().strip());
            } catch (NumberFormatException e) {
                return "ERROR: limit 必须是整数";
            }
        }
        if (limit < 1) {
            return "ERROR: limit 必须大于等于 1";
        }
        limit = Math.min(limit, MAX_LIMIT);

        String rawPath = ToolSupport.stringArg(args, "path");
        Path base;
        try {
            base = paths.resolve(rawPath == null ? "." : rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.isDirectory(base)) {
            return "ERROR: 不是目录: " + (rawPath == null ? "." : rawPath);
        }

        PathMatcher matcher;
        PathMatcher topLevel = null;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalized);
            // Java 的 **/ 至少要一层目录；用户写 **/*.java 时也应命中 base 下的顶层文件
            if (normalized.startsWith("**/")) {
                topLevel = FileSystems.getDefault().getPathMatcher("glob:" + normalized.substring(3));
            }
        } catch (RuntimeException e) {
            return "ERROR: glob 非法: " + e.getMessage();
        }

        List<Hit> hits = new ArrayList<>();
        int[] visited = {0};
        PathMatcher top = topLevel;
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (!dir.equals(base) && (SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        || !paths.allowsDirectory(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (++visited[0] > MAX_VISITED) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = base.relativize(file);
                    if ((matcher.matches(relative) || (top != null && top.matches(relative))) && paths.allows(file)) {
                        hits.add(new Hit(paths.display(file), attributes.lastModifiedTime().toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "ERROR: 遍历失败: " + e.getMessage();
        }
        if (hits.isEmpty()) {
            return "没有匹配的文件: " + pattern;
        }
        hits.sort(Comparator.comparingLong(Hit::modified).reversed().thenComparing(Hit::path));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(hits.get(i).path());
        }
        if (hits.size() > limit) {
            out.append("\n… 共 ").append(hits.size()).append(" 个匹配，仅显示最近修改的前 ").append(limit)
                .append(" 个；收窄 pattern 或 path");
        } else {
            out.append("\n（共 ").append(hits.size()).append(" 个）");
        }
        if (visited[0] > MAX_VISITED) {
            out.append("\n… 目录过大，只扫描了前 ").append(MAX_VISITED).append(" 个文件");
        }
        return out.toString();
    }

    private record Hit(String path, long modified) {
    }
}
