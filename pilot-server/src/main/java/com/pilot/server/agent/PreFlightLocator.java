package com.pilot.server.agent;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.tool.file.SearchTool;
import com.pilot.core.util.Texts;
import com.pilot.core.workspace.PathValidator;

/**
 * 零模型调用、跨平台限时的 Pre-flight 候选目标预定位器。
 *
 * <p>遵循「宁缺毋滥 + Fail-open」原则：只从任务描述抽取高置信路径/标识符/字面量锚点，
 * 单锚点命中过多文件视为泛词丢弃；无高置信命中或超时时返回空结果，绝不阻塞启动或注入噪声。
 */
final class PreFlightLocator {
    static final long DEFAULT_SOFT_BUDGET_MS = 300L;
    static final long DEFAULT_HARD_BUDGET_MS = 500L;
    static final int DEFAULT_MAX_CANDIDATES = 6;
    static final int MAX_RENDER_CHARS = 1_200;
    static final int MAX_ANCHORS = 8;
    static final int MAX_FILES_PER_ANCHOR = 20;
    static final int MIN_SCORE = 50;
    private static final int PATH_WALK_LIMIT = 3_000;
    private static final int CONTENT_WALK_LIMIT = 1_500;
    private static final long MAX_SCAN_FILE_BYTES = 512 * 1024L;

    private static final int TIER_PATH = 100;
    private static final int TIER_IDENTIFIER = 70;
    private static final int TIER_LITERAL = 50;

    private static final Pattern TRIVIAL_INPUT =
        Pattern.compile("^(?:你好|您好|嗨|hi|hello|ok|okay|好的|好|可以|继续|开始|开始吧|谢谢|thanks|thank you|收到|没问题)[！!。.~～\\s]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_OR_FILE = Pattern.compile(
        "(?<![\\w./\\\\-])([\\w.-]+(?:[/\\\\][\\w.-]+)+\\.[A-Za-z0-9]{1,8}|[A-Za-z0-9_.-]+\\.(?:java|kt|kts|ts|tsx|js|jsx|mjs|cjs|py|go|rs|md|yml|yaml|json|sql|xml|css|scss|html|sh|cmd|ps1|c|h|cpp))(?:[:#L](\\d+))?(?![\\w.-])");
    private static final Pattern CAMEL_IDENTIFIER =
        Pattern.compile("(?<![\\w./\\\\-])([A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]+)+|[a-z]+[A-Z][A-Za-z0-9]{2,})(?![\\w-])");
    private static final Pattern DELIMITED_IDENTIFIER =
        Pattern.compile("(?<![\\w./\\\\-])([A-Za-z][A-Za-z0-9]*(?:[_-][A-Za-z0-9]+)+)(?![\\w-])");
    private static final Pattern ROUTE_ANCHOR = Pattern.compile("(?<![\\w])(/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_{}:-]+)+)");
    private static final Pattern BACKTICK_OR_QUOTED =
        Pattern.compile("`([^`\\r\\n]{4,80})`|\"([^\"\\r\\n]{4,80})\"|「([^」\\r\\n]{4,80})」");

    private static final Set<String> STOP_WORDS =
        Set.of("error", "config", "service", "controller", "manager", "helper", "utils", "index", "main", "test",
            "tests", "readme", "build", "target", "status", "result", "state", "value", "default", "false", "true",
            "null", "string", "object", "number", "boolean", "button", "input", "output", "message", "request",
            "response", "client", "server", "model", "agent", "pilot", "window", "system", "local", "start", "update",
            "create", "delete", "check", "enable", "disable", "failed", "success", "warning", "package");

    record Candidate(String displayPath, int score, String reason) {
    }

    record Result(List<Candidate> candidates, long elapsedMs, int anchorCount, boolean timedOut, String skipReason) {
        static Result empty(long elapsedMs, int anchorCount, boolean timedOut, String skipReason) {
            return new Result(List.of(), elapsedMs, anchorCount, timedOut, skipReason);
        }

        boolean hasCandidates() {
            return !candidates.isEmpty();
        }

        Set<String> candidatePaths() {
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            for (Candidate candidate : candidates) {
                paths.add(candidate.displayPath());
            }
            return Set.copyOf(paths);
        }

        /** 渲染为尾部非持久化候选块，严格控制在 {@link #MAX_RENDER_CHARS} 字符以内。 */
        String renderBlock() {
            if (candidates.isEmpty()) {
                return "";
            }
            String header = "<candidate-targets status=\"unverified-hypothesis\">\n"
                + "以下是按当前任务关键词自动初筛的候选定位点（仅为初始假设，需先 read_file 验证；若方向不符可按扩展条件重新检索）：\n";
            String footer = "</candidate-targets>";
            StringBuilder out = new StringBuilder(header);
            int added = 0;
            for (Candidate candidate : candidates) {
                String line = "- " + candidate.displayPath() + "（" + candidate.reason() + "）\n";
                if (out.length() + line.length() + footer.length() > MAX_RENDER_CHARS) {
                    break;
                }
                out.append(line);
                added++;
            }
            if (added == 0) {
                return "";
            }
            out.append(footer);
            return out.toString();
        }
    }

    private enum AnchorKind {
        PATH, IDENTIFIER, LITERAL
    }

    private record Anchor(String text, AnchorKind kind, int weight) {
    }

    private final boolean allowRipgrep;
    private final long softBudgetMs;
    private final long hardBudgetMs;
    private final int maxCandidates;

    PreFlightLocator() {
        this(true, DEFAULT_SOFT_BUDGET_MS, DEFAULT_HARD_BUDGET_MS, DEFAULT_MAX_CANDIDATES);
    }

    PreFlightLocator(boolean allowRipgrep, long softBudgetMs, long hardBudgetMs, int maxCandidates) {
        this.allowRipgrep = allowRipgrep;
        this.softBudgetMs = Math.max(20L, softBudgetMs);
        this.hardBudgetMs = Math.max(this.softBudgetMs, hardBudgetMs);
        this.maxCandidates = Math.max(1, Math.min(8, maxCandidates));
    }

    private record ScanContext(Path root, PathValidator validator, Set<String> excluded, long deadline,
        boolean[] timedOut, Map<String, Integer> scores, Map<String, LinkedHashSet<String>> reasons) {
    }

    Result locate(Path workspaceRoot, PathValidator validator, String queryText, Set<String> excludePaths) {
        long startedNs = System.nanoTime();
        if (workspaceRoot == null || validator == null || !Files.isDirectory(validator.root())) {
            return Result.empty(0L, 0, false, "invalid_workspace");
        }
        String query = queryText == null ? "" : queryText.strip();
        if (query.length() < 6 || TRIVIAL_INPUT.matcher(query).matches()) {
            return Result.empty(elapsedMs(startedNs), 0, false, "trivial_input");
        }
        List<Anchor> anchors = extractAnchors(query);
        if (anchors.isEmpty()) {
            return Result.empty(elapsedMs(startedNs), 0, false, "no_anchors");
        }

        Path root = validator.root();
        Set<String> excluded = normalizeExcluded(excludePaths);
        long softDeadline = startedNs + TimeUnit.MILLISECONDS.toNanos(softBudgetMs);
        long hardDeadline = startedNs + TimeUnit.MILLISECONDS.toNanos(hardBudgetMs);
        boolean[] timedOut = {false};
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> reasons = new LinkedHashMap<>();
        ScanContext base = new ScanContext(root, validator, excluded, softDeadline, timedOut, scores, reasons);

        locatePathAndStemAnchors(base, anchors, startedNs);
        locateContentAnchors(base, anchors, hardDeadline);
        List<Candidate> ranked = rankCandidates(scores, reasons, excluded);

        long elapsed = elapsedMs(startedNs);
        if (ranked.isEmpty()) {
            return Result.empty(elapsed, anchors.size(), timedOut[0], timedOut[0] ? "timeout" : "below_threshold");
        }
        return new Result(ranked, elapsed, anchors.size(), timedOut[0], "");
    }

    private void locatePathAndStemAnchors(ScanContext base, List<Anchor> anchors, long startedNs) {
        // 1. Tier 1 路径与文件名匹配 + Tier 2 类型标识符的精确定义文件名匹配（如 GoalExecutionPolicy -> GoalExecutionPolicy.java）
        List<Anchor> unresolvedFileNames = new ArrayList<>();
        List<Anchor> stemAnchors = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (anchor.kind() == AnchorKind.PATH) {
                String normalized = anchor.text().replace('\\', '/');
                if (!tryResolveDirectPath(base.validator(), normalized, base.excluded(), base.scores(),
                    base.reasons())) {
                    unresolvedFileNames.add(new Anchor(normalized, AnchorKind.PATH, anchor.weight()));
                }
            } else if (anchor.kind() == AnchorKind.IDENTIFIER && looksLikeTypeStem(anchor.text())) {
                stemAnchors.add(anchor);
            }
        }
        if ((!unresolvedFileNames.isEmpty() || !stemAnchors.isEmpty()) && System.nanoTime() < base.deadline()) {
            long pathDeadline =
                Math.min(base.deadline(), startedNs + TimeUnit.MILLISECONDS.toNanos(Math.min(200L, softBudgetMs)));
            ScanContext scan = new ScanContext(base.root(), base.validator(), base.excluded(), pathDeadline,
                base.timedOut(), base.scores(), base.reasons());
            scanFileNames(scan, unresolvedFileNames, stemAnchors);
        }
    }

    private void locateContentAnchors(ScanContext base, List<Anchor> anchors, long hardDeadline) {
        // 2. Tier 2/3 内容锚点批查（rg -> git grep -> 受限 Java 扫描三层跨平台回落）
        List<Anchor> contentAnchors = anchors.stream().filter(a -> a.kind() != AnchorKind.PATH).toList();
        if (contentAnchors.isEmpty() || System.nanoTime() >= base.deadline()) {
            return;
        }
        Map<String, Map<String, Integer>> hitsByAnchor = allowRipgrep
            ? runRipgrepBatch(base.root(), base.validator(), contentAnchors, hardDeadline, base.timedOut()) : null;
        if (hitsByAnchor == null && allowRipgrep && Files.exists(base.root().resolve(".git"))
            && System.nanoTime() < hardDeadline) {
            hitsByAnchor =
                runGitGrepBatch(base.root(), base.validator(), contentAnchors, hardDeadline, base.timedOut());
        }
        if (hitsByAnchor == null && System.nanoTime() < hardDeadline) {
            hitsByAnchor =
                fallbackContentScan(base.root(), base.validator(), contentAnchors, hardDeadline, base.timedOut());
        }
        if (hitsByAnchor != null) {
            scoreContentHits(contentAnchors, hitsByAnchor, base.excluded(), base.scores(), base.reasons());
        }
    }

    private List<Candidate> rankCandidates(Map<String, Integer> scores, Map<String, LinkedHashSet<String>> reasons,
        Set<String> excluded) {
        // 生产源码在已达标候选中略优于测试/文档，避免多个单测文件挤掉核心实现文件
        for (String path : List.copyOf(scores.keySet())) {
            if (scores.getOrDefault(path, 0) >= MIN_SCORE && !isTestOrDocPath(path)) {
                scores.merge(path, 15, Integer::sum);
            }
        }
        return scores.entrySet().stream()
            .filter(entry -> entry.getValue() >= MIN_SCORE && !excluded.contains(entry.getKey()))
            .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(maxCandidates)
            .map(entry -> new Candidate(entry.getKey(), entry.getValue(), formatReasons(reasons.get(entry.getKey()))))
            .toList();
    }

    static List<Anchor> extractAnchors(String query) {
        LinkedHashMap<String, Anchor> found = new LinkedHashMap<>();
        extractPathAndRouteAnchors(query, found);
        extractIdentifierAnchors(query, found);
        extractQuotedAnchors(query, found);
        return found.values().stream().sorted(Comparator.comparingInt(Anchor::weight).reversed()
            .thenComparingInt((Anchor a) -> a.text().length()).reversed()).limit(MAX_ANCHORS).toList();
    }

    private static void extractPathAndRouteAnchors(String query, Map<String, Anchor> found) {
        Matcher pathMatcher = PATH_OR_FILE.matcher(query);
        while (pathMatcher.find()) {
            String clean = stripLeadingDotSlash(pathMatcher.group(1).strip().replace('\\', '/'));
            if (clean.length() >= 4 && !isStopWord(clean)) {
                addAnchor(found, clean, AnchorKind.PATH, TIER_PATH);
            }
        }
        Matcher routeMatcher = ROUTE_ANCHOR.matcher(query);
        while (routeMatcher.find()) {
            String route = routeMatcher.group(1).strip();
            if (route.length() >= 5 && !route.startsWith("//") && !looksLikeFilePath(route)) {
                addAnchor(found, route, AnchorKind.IDENTIFIER, TIER_IDENTIFIER);
            }
        }
    }

    private static void extractIdentifierAnchors(String query, Map<String, Anchor> found) {
        Matcher camelMatcher = CAMEL_IDENTIFIER.matcher(query);
        while (camelMatcher.find()) {
            String id = camelMatcher.group(1);
            if (id.length() >= 6 && !isStopWord(id)) {
                addAnchor(found, id, AnchorKind.IDENTIFIER, TIER_IDENTIFIER);
            }
        }
        Matcher delimMatcher = DELIMITED_IDENTIFIER.matcher(query);
        while (delimMatcher.find()) {
            String id = delimMatcher.group(1);
            if (id.length() >= 6 && !isStopWord(id) && !looksLikeVersionOrDate(id)) {
                addAnchor(found, id, AnchorKind.IDENTIFIER, TIER_IDENTIFIER);
            }
        }
    }

    private static void extractQuotedAnchors(String query, Map<String, Anchor> found) {
        Matcher quotedMatcher = BACKTICK_OR_QUOTED.matcher(query);
        while (quotedMatcher.find()) {
            String literal = quotedMatcher.group(1) != null ? quotedMatcher.group(1)
                : quotedMatcher.group(2) != null ? quotedMatcher.group(2) : quotedMatcher.group(3);
            if (literal == null) {
                continue;
            }
            String trimmed = literal.strip();
            if (trimmed.length() < 4 || trimmed.length() > 80 || isStopWord(trimmed)) {
                continue;
            }
            if (PATH_OR_FILE.matcher(trimmed).matches()) {
                addAnchor(found, stripLeadingDotSlash(trimmed.replace('\\', '/')), AnchorKind.PATH, TIER_PATH);
            } else if (!trimmed.contains("  ")) {
                addAnchor(found, trimmed, AnchorKind.LITERAL, TIER_LITERAL);
            }
        }
    }

    private static void addAnchor(Map<String, Anchor> found, String text, AnchorKind kind, int weight) {
        String key = text.toLowerCase(Locale.ROOT);
        Anchor existing = found.get(key);
        if (existing == null || existing.weight() < weight) {
            found.put(key, new Anchor(text, kind, weight));
        }
    }

    private boolean tryResolveDirectPath(PathValidator validator, String rawPath, Set<String> excluded,
        Map<String, Integer> scores, Map<String, LinkedHashSet<String>> reasons) {
        try {
            Path resolved = validator.resolve(rawPath);
            if (!Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved) || !validator.allows(resolved)) {
                return false;
            }
            String display = validator.display(resolved);
            if (isSensitivePath(display) || excluded.contains(display)) {
                return true;
            }
            scores.merge(display, TIER_PATH, Integer::sum);
            reasons.computeIfAbsent(display, ignored -> new LinkedHashSet<>()).add("路径精确匹配");
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void scanFileNames(ScanContext scan, List<Anchor> fileAnchors, List<Anchor> stemAnchors) {
        if (allowRipgrep && Files.exists(scan.root().resolve(".git"))
            && scanGitFileNames(scan, fileAnchors, stemAnchors)) {
            return;
        }
        int[] visited = {0};
        try {
            Files.walkFileTree(scan.root(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (System.nanoTime() > scan.deadline()) {
                        scan.timedOut()[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!dir.equals(scan.root())
                        && (attrs.isSymbolicLink() || shouldSkipDirectory(dir.getFileName().toString())
                            || !scan.validator().allowsDirectory(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (System.nanoTime() > scan.deadline()) {
                        scan.timedOut()[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (++visited[0] > PATH_WALK_LIMIT) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isSymbolicLink() || !scan.validator().allows(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    matchFileCandidate(scan.validator().display(file), file.getFileName().toString(), fileAnchors,
                        stemAnchors, scan.excluded(), scan.scores(), scan.reasons());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 扫描失败静默降级
        }
    }

    private boolean scanGitFileNames(ScanContext scan, List<Anchor> fileAnchors, List<Anchor> stemAnchors) {
        long remainingMs = Math.max(15L, TimeUnit.NANOSECONDS.toMillis(scan.deadline() - System.nanoTime()));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard")
                .directory(scan.root().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            process = builder.start();
            Process running = process;
            CompletableFuture<byte[]> output =
                CompletableFuture.supplyAsync(() -> readLimited(running.getInputStream()));
            if (!process.waitFor(remainingMs, TimeUnit.MILLISECONDS)) {
                scan.timedOut()[0] = true;
                process.destroyForcibly();
                output.cancel(true);
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            byte[] bytes = output.get(Math.max(20L, Math.min(150L, remainingMs)), TimeUnit.MILLISECONDS);
            String text = new String(bytes, StandardCharsets.UTF_8);
            for (String rawLine : text.lines().toList()) {
                String relative = stripLeadingDotSlash(rawLine.strip().replace('\\', '/'));
                if (relative.isEmpty()) {
                    continue;
                }
                int slash = relative.lastIndexOf('/');
                String fileName = slash >= 0 ? relative.substring(slash + 1) : relative;
                // 先在纯内存完成文件名/类型定义名匹配，再对命中的极少数候选执行磁盘路径与权限校验，避免全仓上千次系统调用拖慢超时。
                if (!matchesAnyFileOrStem(relative, fileName, fileAnchors, stemAnchors)) {
                    continue;
                }
                Path resolved;
                try {
                    resolved = scan.validator().resolve(relative);
                    if (!scan.validator().allows(resolved) || Files.isSymbolicLink(resolved)
                        || !Files.isRegularFile(resolved)) {
                        continue;
                    }
                } catch (RuntimeException invalid) {
                    continue;
                }
                matchFileCandidate(scan.validator().display(resolved), resolved.getFileName().toString(), fileAnchors,
                    stemAnchors, scan.excluded(), scan.scores(), scan.reasons());
            }
            return true;
        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            scan.timedOut()[0] = true;
            return false;
        }
    }

    private static boolean matchesAnyFileOrStem(String display, String fileName, List<Anchor> fileAnchors,
        List<Anchor> stemAnchors) {
        String lowerDisplay = display.toLowerCase(Locale.ROOT);
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String lowerStem = fileStem(lowerName);
        for (Anchor anchor : fileAnchors) {
            String target = anchor.text().toLowerCase(Locale.ROOT);
            if (target.contains("/") ? lowerDisplay.equals(target) || lowerDisplay.endsWith("/" + target)
                : lowerName.equals(target)) {
                return true;
            }
        }
        for (Anchor anchor : stemAnchors) {
            if (lowerStem.equals(anchor.text().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void matchFileCandidate(String display, String fileName, List<Anchor> fileAnchors,
        List<Anchor> stemAnchors, Set<String> excluded, Map<String, Integer> scores,
        Map<String, LinkedHashSet<String>> reasons) {
        if (isSensitivePath(display) || excluded.contains(display)) {
            return;
        }
        String lowerDisplay = display.toLowerCase(Locale.ROOT);
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String lowerStem = fileStem(lowerName);
        for (Anchor anchor : fileAnchors) {
            String target = anchor.text().toLowerCase(Locale.ROOT);
            boolean matched = target.contains("/") ? lowerDisplay.equals(target) || lowerDisplay.endsWith("/" + target)
                : lowerName.equals(target);
            if (matched) {
                scores.merge(display, anchor.weight(), Integer::sum);
                reasons.computeIfAbsent(display, ignored -> new LinkedHashSet<>()).add("文件名匹配 " + anchor.text());
            }
        }
        for (Anchor anchor : stemAnchors) {
            if (lowerStem.equals(anchor.text().toLowerCase(Locale.ROOT))) {
                scores.merge(display, 90, Integer::sum);
                reasons.computeIfAbsent(display, ignored -> new LinkedHashSet<>()).add("定义文件匹配 " + anchor.text());
            }
        }
    }

    /** 无 rg 时的 git 仓库高速回落：单进程 git grep -n -F -I，跨 Windows/macOS/Linux 自动跳过二进制与忽略目录。 */
    private Map<String, Map<String, Integer>> runGitGrepBatch(Path root, PathValidator validator, List<Anchor> anchors,
        long hardDeadline, boolean[] timedOut) {
        long remainingMs = Math.max(15L, TimeUnit.NANOSECONDS.toMillis(hardDeadline - System.nanoTime()));
        ArrayList<String> command =
            new ArrayList<>(List.of("git", "--no-pager", "grep", "--no-color", "-n", "-F", "-I"));
        for (Anchor anchor : anchors) {
            command.add("-e");
            command.add(anchor.text());
        }
        Process process = null;
        try {
            ProcessBuilder builder =
                new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            process = builder.start();
            Process running = process;
            CompletableFuture<byte[]> output =
                CompletableFuture.supplyAsync(() -> readLimited(running.getInputStream()));
            if (!process.waitFor(remainingMs, TimeUnit.MILLISECONDS)) {
                timedOut[0] = true;
                process.destroyForcibly();
                output.cancel(true);
                return Map.of();
            }
            byte[] bytes = output.get(Math.max(20L, Math.min(200L, remainingMs)), TimeUnit.MILLISECONDS);
            int status = process.exitValue();
            if (status > 1) {
                return null;
            }
            return parseLineMatches(validator, anchors, new String(bytes, StandardCharsets.UTF_8));
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
            return Map.of();
        } catch (ExecutionException | TimeoutException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            timedOut[0] = true;
            return Map.of();
        }
    }

    private static Map<String, Map<String, Integer>> parseLineMatches(PathValidator validator, List<Anchor> anchors,
        String text) {
        Map<String, Map<String, Integer>> hitsByAnchor = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            int first = line.indexOf(':');
            int second = first < 0 ? -1 : line.indexOf(':', first + 1);
            if (second < 0) {
                continue;
            }
            String rawFile = stripLeadingDotSlash(line.substring(0, first).replace('\\', '/'));
            int lineNo;
            try {
                lineNo = Integer.parseInt(line.substring(first + 1, second));
            } catch (NumberFormatException ignored) {
                continue;
            }
            Path resolved;
            try {
                resolved = validator.resolve(rawFile);
                if (!validator.allows(resolved) || Files.isSymbolicLink(resolved)) {
                    continue;
                }
            } catch (RuntimeException invalid) {
                continue;
            }
            String display = validator.display(resolved);
            if (isSensitivePath(display)) {
                continue;
            }
            String content = line.substring(second + 1);
            for (Anchor anchor : anchors) {
                if (content.contains(anchor.text())) {
                    hitsByAnchor.computeIfAbsent(anchor.text(), ignored -> new LinkedHashMap<>()).putIfAbsent(display,
                        lineNo);
                }
            }
        }
        return hitsByAnchor;
    }

    /** 单进程调用 rg -F -e 批量检索全部内容锚点，避免 Windows 多次 CreateProcess 开销。 */
    private Map<String, Map<String, Integer>> runRipgrepBatch(Path root, PathValidator validator, List<Anchor> anchors,
        long hardDeadline, boolean[] timedOut) {
        long remainingMs = Math.max(10L, TimeUnit.NANOSECONDS.toMillis(hardDeadline - System.nanoTime()));
        ArrayList<String> command = new ArrayList<>(List.of("rg", "--fixed-strings", "--line-number", "--no-heading",
            "--color", "never", "--no-messages", "--with-filename", "--max-count", "25"));
        for (String skipped : SearchTool.SKIPPED_DIRECTORIES) {
            command.add("--glob");
            command.add("!" + skipped + "/**");
        }
        for (Anchor anchor : anchors) {
            command.add("-e");
            command.add(anchor.text());
        }
        command.add("--");
        command.add(".");
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = process;
            CompletableFuture<byte[]> output =
                CompletableFuture.supplyAsync(() -> readLimited(running.getInputStream()));
            if (!process.waitFor(remainingMs, TimeUnit.MILLISECONDS)) {
                timedOut[0] = true;
                process.destroyForcibly();
                output.cancel(true);
                return Map.of();
            }
            byte[] bytes = output.get(Math.max(20L, Math.min(200L, remainingMs)), TimeUnit.MILLISECONDS);
            int status = process.exitValue();
            if (status > 1) {
                return null;
            }
            return parseLineMatches(validator, anchors, new String(bytes, StandardCharsets.UTF_8));
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
            return Map.of();
        } catch (ExecutionException | TimeoutException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            timedOut[0] = true;
            return Map.of();
        }
    }

    private Map<String, Map<String, Integer>> fallbackContentScan(Path root, PathValidator validator,
        List<Anchor> anchors, long hardDeadline, boolean[] timedOut) {
        Map<String, Map<String, Integer>> hitsByAnchor = new LinkedHashMap<>();
        int[] visited = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (System.nanoTime() > hardDeadline) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!dir.equals(root) && (attrs.isSymbolicLink()
                        || shouldSkipDirectory(dir.getFileName().toString()) || !validator.allowsDirectory(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (System.nanoTime() > hardDeadline) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (++visited[0] > CONTENT_WALK_LIMIT) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isSymbolicLink() || attrs.size() > MAX_SCAN_FILE_BYTES || !validator.allows(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String display = validator.display(file);
                    if (isSensitivePath(display)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        byte[] bytes = Files.readAllBytes(file);
                        if (Texts.isBinary(bytes)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String content = new String(bytes, StandardCharsets.UTF_8);
                        for (Anchor anchor : anchors) {
                            Map<String, Integer> anchorFiles =
                                hitsByAnchor.computeIfAbsent(anchor.text(), ignored -> new LinkedHashMap<>());
                            if (anchorFiles.size() > MAX_FILES_PER_ANCHOR) {
                                continue;
                            }
                            int pos = content.indexOf(anchor.text());
                            if (pos >= 0) {
                                anchorFiles.putIfAbsent(display, lineNumberAt(content, pos));
                            }
                        }
                    } catch (IOException ignored) {
                        // 跳过不可读文件
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 扫描失败静默降级
        }
        return hitsByAnchor;
    }

    private static void scoreContentHits(List<Anchor> contentAnchors, Map<String, Map<String, Integer>> hitsByAnchor,
        Set<String> excluded, Map<String, Integer> scores, Map<String, LinkedHashSet<String>> reasons) {
        Map<String, Anchor> byText = new HashMap<>();
        for (Anchor anchor : contentAnchors) {
            byText.put(anchor.text(), anchor);
        }
        for (Map.Entry<String, Map<String, Integer>> entry : hitsByAnchor.entrySet()) {
            Anchor anchor = byText.get(entry.getKey());
            Map<String, Integer> fileHits = entry.getValue();
            if (anchor == null || fileHits == null || fileHits.isEmpty()) {
                continue;
            }
            long codeHits = fileHits.keySet().stream().filter(path -> !isDocOrReportPath(path)).count();
            long effectiveCount = codeHits > 0 ? codeHits : fileHits.size();
            if (effectiveCount > MAX_FILES_PER_ANCHOR) {
                continue;
            }
            for (Map.Entry<String, Integer> fileHit : fileHits.entrySet()) {
                String path = fileHit.getKey();
                if (excluded.contains(path)) {
                    continue;
                }
                int weight = isDocOrReportPath(path) ? Math.max(10, anchor.weight() / 3) : anchor.weight();
                scores.merge(path, weight, Integer::sum);
                reasons.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                    .add("命中 " + anchor.text() + " (L" + fileHit.getValue() + ")");
            }
        }
    }

    private static String formatReasons(LinkedHashSet<String> items) {
        if (items == null || items.isEmpty()) {
            return "相关候选";
        }
        return items.stream().limit(2).reduce((a, b) -> a + "、" + b).orElse("相关候选");
    }

    private static int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static byte[] readLimited(InputStream input) {
        try (input) {
            return input.readNBytes(2 * 1024 * 1024);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static boolean isSensitivePath(String displayPath) {
        String lower = displayPath.toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        String name = slash < 0 ? lower : lower.substring(slash + 1);
        return name.equals(".env") || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key")
            || name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".jks") || name.equals("id_rsa")
            || name.equals("id_ed25519") || name.contains("credential") || name.contains("secret");
    }

    private static Set<String> normalizeExcluded(Set<String> excludePaths) {
        if (excludePaths == null || excludePaths.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String path : excludePaths) {
            if (path != null && !path.isBlank()) {
                result.add(stripLeadingDotSlash(path.strip().replace('\\', '/')));
            }
        }
        return result;
    }

    private static String stripLeadingDotSlash(String path) {
        String current = path;
        while (current.startsWith("./")) {
            current = current.substring(2);
        }
        return current;
    }

    private static boolean isStopWord(String text) {
        return STOP_WORDS.contains(text.toLowerCase(Locale.ROOT));
    }

    private static boolean looksLikeFilePath(String value) {
        return value.matches(".*\\.[A-Za-z0-9]{1,6}$");
    }

    private static boolean looksLikeVersionOrDate(String value) {
        return value.matches("^[vV]?\\d+(?:[-_]\\d+)+$");
    }

    private static boolean looksLikeTypeStem(String value) {
        return value.length() >= 6 && Character.isUpperCase(value.charAt(0))
            && value.chars().anyMatch(Character::isLowerCase);
    }

    private static String fileStem(String lowerFileName) {
        int dot = lowerFileName.lastIndexOf('.');
        return dot > 0 ? lowerFileName.substring(0, dot) : lowerFileName;
    }

    private static boolean shouldSkipDirectory(String name) {
        return SearchTool.SKIPPED_DIRECTORIES.contains(name) || (name.startsWith(".") && !".github".equals(name));
    }

    private static boolean isDocOrReportPath(String displayPath) {
        String lower = displayPath.toLowerCase(Locale.ROOT);
        return lower.startsWith("openspec/") || lower.startsWith("evaluation/") || lower.startsWith("docs/")
            || lower.startsWith("docs-public/") || lower.endsWith(".md");
    }

    private static boolean isTestOrDocPath(String displayPath) {
        String lower = displayPath.toLowerCase(Locale.ROOT);
        return isDocOrReportPath(displayPath) || lower.contains("/test/") || lower.endsWith("test.java")
            || lower.endsWith(".test.ts") || lower.endsWith(".test.tsx") || lower.endsWith(".spec.ts");
    }

    private static long elapsedMs(long startedNs) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs));
    }
}
