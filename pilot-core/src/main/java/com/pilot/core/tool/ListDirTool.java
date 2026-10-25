package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 列出工作区内的一个目录，标记子目录与文件大小。 */
public final class ListDirTool implements Tool {
    private static final int MAX_ENTRIES = 300;

    private final PathValidator paths;

    public ListDirTool(PathValidator paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "列出工作区内的目录内容。path 省略时列出工作区根目录；子目录以 / 结尾。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "工作区内的相对目录路径，省略表示根目录")),
                "required", List.of(),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        String target = rawPath == null ? "." : rawPath;
        Path dir;
        try {
            dir = paths.resolve(target);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.isDirectory(dir)) {
            return "ERROR: 不是目录: " + target;
        }
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            int limit = Math.min(entries.size(), MAX_ENTRIES);
            StringBuilder out = new StringBuilder()
                    .append("目录: ").append(paths.display(dir))
                    .append("（").append(entries.size()).append(" 项）");
            for (int i = 0; i < limit; i++) {
                Path entry = entries.get(i);
                out.append('\n');
                if (Files.isDirectory(entry)) {
                    out.append(entry.getFileName()).append('/');
                } else {
                    out.append(entry.getFileName()).append("  (").append(sizeOf(entry)).append(" B)");
                }
            }
            if (entries.size() > limit) {
                out.append("\n… 已截断（仅显示前 ").append(limit).append(" 项）");
            }
            return out.toString();
        } catch (IOException e) {
            return "ERROR: 读取目录失败: " + e.getMessage();
        }
    }

    private static long sizeOf(Path entry) {
        try {
            return Files.size(entry);
        } catch (IOException e) {
            return -1;
        }
    }
}
