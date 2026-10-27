package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "正则表达式"),
                        "path", Map.of("type", "string", "description", "工作区内的相对路径，省略表示根目录"),
                        "glob", Map.of("type", "string", "description", "文件 glob，例如 *.java")),
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

        String glob = ToolSupport.stringArg(args, "glob");
        List<String> matches = runRipgrep(base, patternText, glob);
        if (matches == null) {
            matches = fallback(base, pattern, glob);
        }
        boolean truncated = matches.size() > MAX_MATCHES;
        if (truncated) {
            matches = matches.subList(0, MAX_MATCHES);
        }
        if (matches.isEmpty()) {
            return "未找到匹配项: " + patternText;
        }
        String body = String.join("\n", matches);
        return truncated ? body + "\n… 结果已截断（上限 " + MAX_MATCHES + " 条）" : body;
    }

    private List<String> runRipgrep(Path base, String regex, String glob) {
        var command = new ArrayList<>(List.of(
                "rg", "--line-number", "--no-heading", "--color", "never", "--no-messages", "--with-filename"));
        for (String skipped : SKIPPED_DIRECTORIES) {
            command.add("--glob");
            command.add("!" + skipped + "/**");
        }
        if (glob != null) {
            command.add("--glob");
            command.add(glob);
        }
        // 正则可能以 - 开头，必须放在 -- 后面，否则 rg 会把它当成自己的参数
        command.add("--");
        command.add(regex);
        command.add(Files.isDirectory(base) ? "." : base.getFileName().toString());
        Process process = null;
        try {
            Path directory = Files.isDirectory(base) ? base : base.getParent();
            process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process running = process;
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readLimited(running.getInputStream()));
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                return null;
            }
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            int status = process.exitValue();
            if (status > 1) return null;
            var matches = new ArrayList<String>();
            String text = new String(bytes, StandardCharsets.UTF_8);
            for (String line : text.lines().toList()) {
                int first = line.indexOf(':');
                int second = first < 0 ? -1 : line.indexOf(':', first + 1);
                if (second < 0) continue;
                String file = line.substring(0, first);
                String lineNumber = line.substring(first + 1, second);
                String displayed = Files.isDirectory(base) ? paths.display(base.resolve(file)) : paths.display(base);
                matches.add(formatMatch(displayed, lineNumber, line.substring(second + 1)));
                if (matches.size() > MAX_MATCHES) break;
            }
            return matches;
        } catch (IOException e) {
            if (process != null) process.destroyForcibly();
            return null;
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    private static byte[] readLimited(InputStream input) {
        try (input) {
            return input.readNBytes(4 * 1024 * 1024 + 1);
        } catch (IOException e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    private List<String> fallback(Path base, Pattern pattern, String glob) {
        var matches = new ArrayList<String>();
        for (Path file : candidateFiles(base, glob)) {
            if (searchFile(file, pattern, matches) && matches.size() > MAX_MATCHES) break;
        }
        return matches;
    }

    private List<Path> candidateFiles(Path base, String glob) {
        if (Files.isRegularFile(base)) {
            return glob == null || matchesGlob(base.getFileName().toString(), glob) ? List.of(base) : List.of();
        }
        try (var stream = Files.walk(base)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !inSkippedDirectory(base, path))
                    .filter(path -> glob == null || matchesGlob(base.relativize(path).toString().replace('\\', '/'), glob))
                    .sorted(Comparator.comparing(paths::display)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean matchesGlob(String path, String glob) {
        String normalized = glob.replace('\\', '/');
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '*') regex.append("[^/]*");
            else if (ch == '?') regex.append("[^/]");
            else regex.append(Pattern.quote(String.valueOf(ch)));
        }
        regex.append('$');
        if (!normalized.contains("/")) path = Path.of(path).getFileName().toString();
        return path.matches(regex.toString());
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
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return false;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (ToolSupport.isBinary(bytes)) return false;
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
            String display = paths.display(file);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    matches.add(formatMatch(display, Integer.toString(i + 1), lines[i]));
                    if (matches.size() > MAX_MATCHES) return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static String formatMatch(String file, String line, String text) {
        String compact = text.strip();
        if (compact.length() > MAX_LINE_CHARS) compact = compact.substring(0, MAX_LINE_CHARS) + "…";
        return file + ":" + line + ": " + compact;
    }
}
