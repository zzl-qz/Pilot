package com.pilot.core.tool.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.util.Texts;
import com.pilot.core.workspace.PathValidator;

import lombok.RequiredArgsConstructor;

/**
 * 在工作区内按正则搜索文本，返回 {@code 路径:行号: 内容}。
 * 跳过构建产物、依赖目录与二进制文件，结果数量有上限。
 */
@RequiredArgsConstructor
public final class SearchTool implements Tool.ParallelReadOnly {
    private static final int MAX_MATCHES = 200;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_LINE_CHARS = 300;
    /** 兜底扫描的时间预算：到点就交出手上已有的匹配，别把整轮对话吊死。 */
    private static final int FALLBACK_BUDGET_SECONDS = 8;
    private static final Set<String> SKIPPED_DIRECTORIES =
        Set.of(".git", ".idea", ".gradle", "target", "build", "node_modules", "out", "dist");

    private final PathValidator paths;

    @Override
    public String name() {
        return ToolNames.SEARCH_TEXT;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("pattern", ToolSchemas.string("正则表达式；先精确定位符号再用宽松模式，避免一次命中过多"), "path",
                ToolSchemas.string("工作区内的相对路径，省略表示根目录"), "glob", ToolSchemas.string("文件 glob，例如 *.java")),
            List.of("pattern"));
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
        ArrayList<String> command = new ArrayList<>(
            List.of("rg", "--line-number", "--no-heading", "--color", "never", "--no-messages", "--with-filename"));
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
            process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = process;
            CompletableFuture<byte[]> output =
                CompletableFuture.supplyAsync(() -> readLimited(running.getInputStream()));
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                return null;
            }
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            int status = process.exitValue();
            if (status > 1) {
                return null;
            }
            ArrayList<String> matches = new ArrayList<String>();
            String text = new String(bytes, StandardCharsets.UTF_8);
            for (String line : text.lines().toList()) {
                int first = line.indexOf(':');
                int second = first < 0 ? -1 : line.indexOf(':', first + 1);
                if (second < 0) {
                    continue;
                }
                String file = line.substring(0, first);
                String lineNumber = line.substring(first + 1, second);
                Path matched = Files.isDirectory(base) ? base.resolve(file) : base;
                // 命中结果也要按子 Agent 视图过滤：rg 会搜遍整棵树
                if (!paths.allows(matched)) {
                    continue;
                }
                matches.add(formatMatch(paths.display(matched), lineNumber, line.substring(second + 1)));
                if (matches.size() > MAX_MATCHES) {
                    break;
                }
            }
            return matches;
        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            if (process != null) {
                process.destroyForcibly();
            }
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

    /**
     * rg 不可用（或超时）时的兜底扫描。
     *
     * <p>以前先把整棵树 collect 成 List 再逐个读，Windows 上扫一个模块目录要二十几秒；
     * 而且完全没有时间上限——真撞上卡住的目录，整轮对话就停在这一格。现在边遍历边匹配，
     * 到预算就把已找到的结果交出去。
     */
    private List<String> fallback(Path base, Pattern pattern, String glob) {
        ArrayList<String> matches = new ArrayList<String>();
        if (Files.isRegularFile(base)) {
            if (glob == null || matchesGlob(base.getFileName().toString(), glob)) {
                searchFile(base, pattern, matches);
            }
            return matches;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FALLBACK_BUDGET_SECONDS);
        try (Stream<Path> stream = Files.walk(base)) {
            Iterator<Path> files = stream.iterator();
            while (matches.size() <= MAX_MATCHES) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                Path file;
                try {
                    if (!files.hasNext()) {
                        break;
                    }
                    file = files.next();
                } catch (RuntimeException e) {
                    // 某个子目录没权限时 walk 会抛 UncheckedIOException：放弃剩余部分
                    break;
                }
                if (!Files.isRegularFile(file) || inSkippedDirectory(base, file) || !paths.allows(file)) {
                    continue;
                }
                if (glob != null && !matchesGlob(base.relativize(file).toString().replace('\\', '/'), glob)) {
                    continue;
                }
                searchFile(file, pattern, matches);
            }
        } catch (IOException e) {
            return matches;
        }
        return matches;
    }

    private static boolean matchesGlob(String path, String glob) {
        String normalized = glob.replace('\\', '/');
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '*') {
                regex.append("[^/]*");
            } else if (ch == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        regex.append('$');
        if (!normalized.contains("/")) {
            path = Path.of(path).getFileName().toString();
        }
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
            if (Texts.isBinary(bytes)) {
                return false;
            }
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
            String display = paths.display(file);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    matches.add(formatMatch(display, Integer.toString(i + 1), lines[i]));
                    if (matches.size() > MAX_MATCHES) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static String formatMatch(String file, String line, String text) {
        String compact = text.strip();
        if (compact.length() > MAX_LINE_CHARS) {
            compact = compact.substring(0, MAX_LINE_CHARS) + "…";
        }
        return file + ":" + line + ": " + compact;
    }
}
