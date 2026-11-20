package com.pilot.core.tool.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.util.Texts;
import com.pilot.core.workspace.PathValidator;

/**
 * 在工作区内按正则搜索文本，支持正文命中、仅文件分布（{@code files_only}）和前后上下文行（{@code context_lines}）。
 * 跳过构建产物、依赖目录与二进制文件，结果数量有上限。
 */
public final class SearchTool implements Tool.ParallelReadOnly {
    private static final int MAX_MATCHES = 200;
    private static final int MAX_CONTEXT_LINES = 5;
    private static final int MAX_CONTEXT_OUTPUT_LINES = 300;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_LINE_CHARS = 300;
    /** 兜底扫描的时间预算：到点就交出手上已有的匹配，别把整轮对话吊死。 */
    private static final int FALLBACK_BUDGET_SECONDS = 8;
    /** 没有 rg 时最多看这么多个文件。依赖目录不进入，避免把 node_modules 整棵读一遍。 */
    private static final int FALLBACK_MAX_FILES = 4_000;
    private static final Pattern RG_LINE_MARKER = Pattern.compile("([:-])(\\d+)\\1");
    public static final Set<String> SKIPPED_DIRECTORIES =
        Set.of(".git", ".idea", ".gradle", ".pilot", ".next", ".nuxt", ".turbo", ".cache", ".venv", "venv", "coverage",
            "__pycache__", "target", "build", "node_modules", "out", "dist");

    private final PathValidator paths;
    private final ReadGuard reads;
    private final boolean allowRipgrep;

    public SearchTool(PathValidator paths) {
        this(paths, ReadGuard.allowAll(), true);
    }

    public SearchTool(PathValidator paths, ReadGuard reads) {
        this(paths, reads, true);
    }

    /** 包级构造：测试可关闭 rg，强制验证无 rg 环境下的 Java 兜底路径。 */
    SearchTool(PathValidator paths, ReadGuard reads, boolean allowRipgrep) {
        this.paths = paths;
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
        this.allowRipgrep = allowRipgrep;
    }

    @Override
    public String name() {
        return ToolNames.SEARCH_TEXT;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", ToolSchemas.string("正则表达式；先精确定位符号再用宽松模式，避免一次命中过多"));
        props.put("path", ToolSchemas.string("工作区内的相对路径，省略表示根目录"));
        props.put("glob", ToolSchemas.string("文件 glob，例如 *.java"));
        props.put("files_only", Map.of("type", "boolean", "default", false, "description",
            "为 true 时仅返回命中文件路径与匹配次数，适合宽搜摸清分布；会忽略 context_lines"));
        props.put("context_lines", Map.of("type", "integer", "minimum", 0, "maximum", MAX_CONTEXT_LINES, "default", 0,
            "description", "每处命中前后附带的上下文行数（0–" + MAX_CONTEXT_LINES + "），常用于直接看清调用片段、省去二次 read_file"));
        return ToolSchemas.object(props, List.of("pattern"));
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
        BooleanArg filesOnlyArg = parseBooleanArg(args, "files_only");
        if (filesOnlyArg.invalid()) {
            return "ERROR: 参数 files_only 必须是布尔值";
        }
        IntArg contextArg = parseIntArg(args, "context_lines");
        if (contextArg.invalid() || (contextArg.present() && contextArg.value() < 0)) {
            return "ERROR: 参数 context_lines 必须是 0 到 " + MAX_CONTEXT_LINES + " 的整数";
        }
        boolean filesOnly = filesOnlyArg.value();
        int contextLines = filesOnly ? 0 : Math.min(contextArg.value(), MAX_CONTEXT_LINES);

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
        if (filesOnly) {
            return executeFilesOnly(base, patternText, pattern, glob);
        }
        return executeContent(base, patternText, pattern, glob, contextLines);
    }

    private String executeFilesOnly(Path base, String patternText, Pattern pattern, String glob) {
        Map<String, Integer> counts = allowRipgrep ? runRipgrepCounts(base, patternText, glob) : null;
        if (counts == null) {
            counts = fallbackCounts(base, pattern, glob);
        }
        if (counts.isEmpty()) {
            return "未找到匹配项: " + patternText;
        }
        List<Map.Entry<String, Integer>> sorted =
            counts.entrySet().stream().sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed().thenComparing(Map.Entry::getKey)).toList();
        boolean truncated = sorted.size() > MAX_MATCHES;
        List<Map.Entry<String, Integer>> shown = truncated ? sorted.subList(0, MAX_MATCHES) : sorted;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            Map.Entry<String, Integer> entry = shown.get(i);
            out.append(entry.getKey()).append(" (").append(entry.getValue()).append(" 处匹配)");
        }
        if (truncated) {
            out.append("\n… 结果已截断（共 ").append(sorted.size()).append(" 个文件，仅显示前 ").append(MAX_MATCHES).append(" 个）");
        }
        return out.toString();
    }

    private String executeContent(Path base, String patternText, Pattern pattern, String glob, int contextLines) {
        int limit = contextLines > 0 ? MAX_CONTEXT_OUTPUT_LINES : MAX_MATCHES;
        List<String> matches = allowRipgrep ? runRipgrep(base, patternText, glob, contextLines, limit) : null;
        if (matches == null) {
            matches = fallback(base, pattern, glob, contextLines, limit);
        }
        boolean truncated = matches.size() > limit;
        if (truncated) {
            matches = new ArrayList<>(matches.subList(0, limit));
            while (!matches.isEmpty() && "--".equals(matches.getLast())) {
                matches.removeLast();
            }
        }
        if (matches.isEmpty()) {
            return "未找到匹配项: " + patternText;
        }
        String body = String.join("\n", matches);
        return truncated ? body + "\n… 结果已截断（上限 " + limit + " 行）" : body;
    }

    private Map<String, Integer> runRipgrepCounts(Path base, String regex, String glob) {
        ArrayList<String> command = new ArrayList<>(
            List.of("rg", "--count", "--no-heading", "--color", "never", "--no-messages", "--with-filename"));
        appendRipgrepFilters(command, glob, regex, base);
        String text = execRipgrep(base, command);
        if (text == null) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            int sep = line.lastIndexOf(':');
            if (sep <= 0 || sep >= line.length() - 1) {
                continue;
            }
            int count;
            try {
                count = Integer.parseInt(line.substring(sep + 1).strip());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (count <= 0) {
                continue;
            }
            String file = line.substring(0, sep);
            Path matched = Files.isDirectory(base) ? base.resolve(file) : base;
            if (!paths.allows(matched)) {
                continue;
            }
            counts.merge(paths.display(matched), count, Integer::sum);
        }
        return counts;
    }

    private List<String> runRipgrep(Path base, String regex, String glob, int contextLines, int limit) {
        ArrayList<String> command = new ArrayList<>(
            List.of("rg", "--line-number", "--no-heading", "--color", "never", "--no-messages", "--with-filename"));
        if (contextLines > 0) {
            command.add("-C");
            command.add(Integer.toString(contextLines));
        }
        appendRipgrepFilters(command, glob, regex, base);
        String text = execRipgrep(base, command);
        if (text == null) {
            return null;
        }
        ArrayList<String> matches = new ArrayList<>();
        Map<String, Boolean> existingFiles = new HashMap<>();
        boolean isDir = Files.isDirectory(base);
        boolean pendingSeparator = false;
        for (String line : text.lines().toList()) {
            if (contextLines > 0 && "--".equals(line)) {
                if (!matches.isEmpty()) {
                    pendingSeparator = true;
                }
                continue;
            }
            ParsedRgLine parsed = parseRipgrepLine(base, isDir, line, contextLines > 0, existingFiles);
            if (parsed == null) {
                continue;
            }
            Path matched = isDir ? base.resolve(parsed.file()) : base;
            // 命中结果也要按子 Agent 视图过滤：rg 会搜遍整棵树
            if (!paths.allows(matched)) {
                continue;
            }
            if (pendingSeparator) {
                matches.add("--");
                pendingSeparator = false;
                if (matches.size() > limit) {
                    break;
                }
            }
            String display = paths.display(matched);
            matches.add(parsed.context() ? formatContext(display, parsed.lineNumber(), parsed.content())
                : formatMatch(display, parsed.lineNumber(), parsed.content()));
            if (matches.size() > limit) {
                break;
            }
        }
        return matches;
    }

    private static void appendRipgrepFilters(List<String> command, String glob, String regex, Path base) {
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
    }

    private String execRipgrep(Path base, List<String> command) {
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
            return new String(bytes, StandardCharsets.UTF_8);
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

    /**
     * 解析 rg 输出行。启用 {@code -C} 时命中行用 {@code :}、上下文行用 {@code -}；
     * 含日期或连字符的文件名（如 {@code 2026-11-20.md}）通过存在性校验消除歧义。
     */
    private static ParsedRgLine parseRipgrepLine(Path base, boolean isDir, String line, boolean hasContext,
        Map<String, Boolean> existingFiles) {
        if (!hasContext) {
            int first = line.indexOf(':');
            int second = first < 0 ? -1 : line.indexOf(':', first + 1);
            if (second < 0) {
                return null;
            }
            return new ParsedRgLine(line.substring(0, first), line.substring(first + 1, second),
                line.substring(second + 1), false);
        }
        if (!isDir) {
            String targetName = base.getFileName().toString();
            if (line.startsWith(targetName) && line.length() > targetName.length() + 2) {
                char sep = line.charAt(targetName.length());
                if (sep == ':' || sep == '-') {
                    int second = line.indexOf(sep, targetName.length() + 1);
                    if (second > targetName.length() + 1) {
                        String num = line.substring(targetName.length() + 1, second);
                        if (allDigits(num)) {
                            return new ParsedRgLine(targetName, num, line.substring(second + 1), sep == '-');
                        }
                    }
                }
            }
        }
        Matcher matcher = RG_LINE_MARKER.matcher(line);
        ParsedRgLine fallbackCandidate = null;
        while (matcher.find()) {
            String file = line.substring(0, matcher.start());
            if (file.isEmpty()) {
                continue;
            }
            boolean context = "-".equals(matcher.group(1));
            ParsedRgLine candidate = new ParsedRgLine(file, matcher.group(2), line.substring(matcher.end()), context);
            if (!isDir) {
                return candidate;
            }
            boolean exists = existingFiles.computeIfAbsent(file, key -> {
                try {
                    return Files.isRegularFile(base.resolve(key));
                } catch (RuntimeException invalid) {
                    return false;
                }
            });
            if (exists) {
                return candidate;
            }
            if (fallbackCandidate == null && !context) {
                fallbackCandidate = candidate;
            }
        }
        return fallbackCandidate;
    }

    private static boolean allDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readLimited(InputStream input) {
        try (input) {
            return input.readNBytes(4 * 1024 * 1024 + 1);
        } catch (IOException e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    private Map<String, Integer> fallbackCounts(Path base, Pattern pattern, String glob) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (Files.isRegularFile(base)) {
            if (glob == null || matchesGlob(base.getFileName().toString(), glob)) {
                int count = countFileMatches(base, pattern);
                if (count > 0) {
                    counts.put(paths.display(base), count);
                }
            }
            return counts;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FALLBACK_BUDGET_SECONDS);
        int[] seen = {0};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (System.nanoTime() > deadline || seen[0] > FALLBACK_MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(base) && (SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        || !paths.allowsDirectory(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (System.nanoTime() > deadline || seen[0]++ > FALLBACK_MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isSymbolicLink() || !paths.allows(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (glob != null && !matchesGlob(base.relativize(file).toString().replace('\\', '/'), glob)) {
                        return FileVisitResult.CONTINUE;
                    }
                    int count = countFileMatches(file, pattern);
                    if (count > 0) {
                        counts.put(paths.display(file), count);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return counts;
        }
        return counts;
    }

    /**
     * rg 不可用（或超时）时的兜底扫描。边遍历边匹配，到时间或文件预算即返回已有结果。
     */
    private List<String> fallback(Path base, Pattern pattern, String glob, int contextLines, int limit) {
        ArrayList<String> matches = new ArrayList<>();
        if (Files.isRegularFile(base)) {
            if (glob == null || matchesGlob(base.getFileName().toString(), glob)) {
                searchFile(base, pattern, contextLines, limit, matches);
            }
            return matches;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FALLBACK_BUDGET_SECONDS);
        int[] seen = {0};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (System.nanoTime() > deadline || seen[0] > FALLBACK_MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(base) && (SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        || !paths.allowsDirectory(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (System.nanoTime() > deadline || matches.size() > limit || seen[0]++ > FALLBACK_MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isSymbolicLink() || !paths.allows(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (glob != null && !matchesGlob(base.relativize(file).toString().replace('\\', '/'), glob)) {
                        return FileVisitResult.CONTINUE;
                    }
                    return searchFile(file, pattern, contextLines, limit, matches) ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
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

    private int countFileMatches(Path file, Pattern pattern) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return 0;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (Texts.isBinary(bytes)) {
                return 0;
            }
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
            int count = 0;
            for (String line : lines) {
                if (pattern.matcher(line).find()) {
                    count++;
                }
            }
            return count;
        } catch (IOException ignored) {
            return 0;
        }
    }

    /** @return 是否因达到上限而提前结束 */
    private boolean searchFile(Path file, Pattern pattern, int contextLines, int limit, List<String> matches) {
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
            if (contextLines <= 0) {
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        matches.add(formatMatch(display, Integer.toString(i + 1), lines[i]));
                        if (matches.size() > limit) {
                            return true;
                        }
                    }
                }
                return false;
            }
            List<Integer> hits = new ArrayList<>();
            Set<Integer> hitSet = new HashSet<>();
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    hits.add(i);
                    hitSet.add(i);
                }
            }
            if (hits.isEmpty()) {
                return false;
            }
            List<int[]> blocks = new ArrayList<>();
            for (int hit : hits) {
                int lo = Math.max(0, hit - contextLines);
                int hi = Math.min(lines.length - 1, hit + contextLines);
                if (!blocks.isEmpty() && lo <= blocks.getLast()[1] + 1) {
                    blocks.getLast()[1] = Math.max(blocks.getLast()[1], hi);
                } else {
                    blocks.add(new int[] {lo, hi});
                }
            }
            for (int[] block : blocks) {
                if (!matches.isEmpty()) {
                    matches.add("--");
                    if (matches.size() > limit) {
                        return true;
                    }
                }
                for (int idx = block[0]; idx <= block[1]; idx++) {
                    String num = Integer.toString(idx + 1);
                    matches.add(hitSet.contains(idx) ? formatMatch(display, num, lines[idx])
                        : formatContext(display, num, lines[idx]));
                    if (matches.size() > limit) {
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
        return file + ":" + line + ": " + compactLine(text);
    }

    private static String formatContext(String file, String line, String text) {
        return file + "-" + line + "- " + compactLine(text);
    }

    private static String compactLine(String text) {
        String compact = text.strip();
        if (compact.length() > MAX_LINE_CHARS) {
            return compact.substring(0, MAX_LINE_CHARS) + "…";
        }
        return compact;
    }

    private static BooleanArg parseBooleanArg(Map<String, Object> args, String name) {
        if (args == null || !args.containsKey(name) || args.get(name) == null) {
            return new BooleanArg(false, false);
        }
        Object raw = args.get(name);
        if (raw instanceof Boolean bool) {
            return new BooleanArg(bool, false);
        }
        if (raw instanceof String text) {
            String stripped = text.strip();
            if ("true".equalsIgnoreCase(stripped)) {
                return new BooleanArg(true, false);
            }
            if ("false".equalsIgnoreCase(stripped)) {
                return new BooleanArg(false, false);
            }
        }
        return new BooleanArg(false, true);
    }

    private static IntArg parseIntArg(Map<String, Object> args, String name) {
        if (args == null || !args.containsKey(name) || args.get(name) == null) {
            return new IntArg(false, 0, false);
        }
        Object raw = args.get(name);
        if (raw instanceof Number number) {
            double numeric = number.doubleValue();
            if (numeric == Math.rint(numeric) && numeric >= Integer.MIN_VALUE && numeric <= Integer.MAX_VALUE) {
                return new IntArg(true, (int)numeric, false);
            }
            return new IntArg(true, 0, true);
        }
        if (raw instanceof String text) {
            try {
                return new IntArg(true, Integer.parseInt(text.strip()), false);
            } catch (NumberFormatException ignored) {
                return new IntArg(true, 0, true);
            }
        }
        return new IntArg(true, 0, true);
    }

    private record BooleanArg(boolean value, boolean invalid) {
    }

    private record IntArg(boolean present, int value, boolean invalid) {
    }

    private record ParsedRgLine(String file, String lineNumber, String content, boolean context) {
    }
}
