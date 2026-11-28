package com.pilot.core.tool.code;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.tool.file.SearchTool;
import com.pilot.core.util.FileNames;
import com.pilot.core.util.Texts;
import com.pilot.core.workspace.PathValidator;

/**
 * 纯本地、跨平台的文件结构大纲与跨文件符号定义搜索器：不依赖外部编译器/LSP，
 * 按扩展名抽取类型级与方法/函数级声明及精确行号。
 */
public final class FileOutline {
    public static final int MAX_SYMBOLS = 120;
    private static final int MAX_SEARCH_RESULTS = 50;
    private static final int MAX_SCAN_FILES = 4000;
    private static final long SEARCH_TIME_BUDGET_NANOS = 3_000_000_000L;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final long MAX_SCAN_FILE_BYTES = 512 * 1024;
    private static final int MAX_SIGNATURE_CHARS = 120;

    private static final Set<String> CONTROL_KEYWORDS = Set.of("if", "else", "for", "while", "switch", "case", "catch",
        "try", "finally", "synchronized", "return", "throw", "new", "super", "this", "assert", "yield", "do", "await",
        "typeof", "delete", "void", "constructor", "import", "export", "from", "package");
    private static final Set<String> CODE_EXTENSIONS = Set.of("java", "kt", "kts", "ts", "tsx", "js", "jsx", "mjs",
        "cjs", "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "rb", "php", "swift", "scala", "md", "markdown");

    private static final Pattern JAVA_TYPE = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*(@?interface|class|record|enum)\\s+([A-Za-z_][\\w$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native|strictfp)\\s+)*(?:<[^>{};]+>\\s+)?(?:[\\w$.<>\\[\\], ?@]+\\s+)?([A-Za-z_][\\w$]*)\\s*\\(([^;{}]*?)\\)\\s*(?:throws\\s+[\\w$.,\\s]+)?(?:\\{|;)");
    private static final Pattern KT_DECL = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|internal|open|final|abstract|sealed|data|value|enum|inner|override|suspend|inline|operator|infix|tailrec|external|companion)\\s+)*(class|interface|object|fun|typealias)\\s+(?:<[^>]+>\\s+)?([A-Za-z_][\\w$.<>]*)");
    private static final Pattern TS_TYPE = Pattern.compile(
        "^\\s*(?:export\\s+(?:default\\s+)?)?(?:declare\\s+)?(?:abstract\\s+)?(class|interface|enum|type|namespace)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern TS_FUNCTION = Pattern.compile(
        "^\\s*(?:export\\s+(?:default\\s+)?)?(?:declare\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*(?:<[^>]+>)?\\s*\\(");
    private static final Pattern TS_ARROW = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?(?:<[^>]+>\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=>");
    private static final Pattern TS_METHOD = Pattern.compile(
        "^\\s{2,}(?:(?:public|protected|private|static|readonly|abstract|override|async|get|set)\\s+)*([A-Za-z_$#][\\w$]*)\\s*(?:<[^>]+>)?\\s*\\([^;]*\\)\\s*(?::\\s*[^{;=]+)?\\{");
    private static final Pattern PYTHON_DECL = Pattern.compile("^(\\s*)(?:async\\s+)?(class|def)\\s+([A-Za-z_]\\w*)");
    private static final Pattern GO_TYPE = Pattern.compile("^\\s*type\\s+([A-Za-z_]\\w*)\\s+(struct|interface|\\S+)");
    private static final Pattern GO_FUNC = Pattern.compile("^\\s*func\\s+(?:\\(([^)]+)\\)\\s+)?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern RUST_TYPE = Pattern.compile(
        "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:unsafe\\s+)?(struct|enum|trait|impl|mod|type)\\s+([A-Za-z_][\\w:]*)");
    private static final Pattern RUST_FN = Pattern
        .compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:const\\s+)?fn\\s+([A-Za-z_]\\w*)");
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,3})\\s+(.+?)\\s*#*$");
    private static final Pattern GENERIC_DECL = Pattern.compile(
        "^\\s*(?:public\\s+|private\\s+|protected\\s+|export\\s+)?(class|interface|enum|struct|trait|func|function|def|fn|type)\\s+([A-Za-z_][\\w$]*)");

    private final PathValidator paths;

    public FileOutline(PathValidator paths) {
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
    }

    public String outline(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
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
            if (Files.size(file) > MAX_FILE_BYTES) {
                return "ERROR: 文件超过 1 MB，无法提取大纲，请改用 search_text 或 read_file: " + rawPath;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (Texts.isBinary(bytes)) {
                return "ERROR: 二进制文件，无法提取大纲: " + rawPath;
            }
            String display = paths.display(file);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
            List<Symbol> symbols = extract(FileNames.extension(display), lines);
            if (symbols.isEmpty()) {
                return "未在 " + display + " 中提取到结构符号（文件共 " + lines.length + " 行），可直接用 read_file 或 search_text。";
            }
            boolean truncated = symbols.size() > MAX_SYMBOLS;
            List<Symbol> shown = truncated ? symbols.subList(0, MAX_SYMBOLS) : symbols;
            StringBuilder out = new StringBuilder();
            out.append(display).append("（共 ").append(lines.length).append(" 行，提取 ").append(symbols.size())
                .append(" 个符号）");
            for (Symbol symbol : shown) {
                out.append('\n');
                if (symbol.nested()) {
                    out.append("  ");
                }
                out.append('L').append(symbol.line()).append(": ").append(symbol.label());
            }
            if (truncated) {
                out.append("\n… 仅显示前 ").append(MAX_SYMBOLS).append(" 个符号（其余 ").append(symbols.size() - MAX_SYMBOLS)
                    .append(" 个已省略）");
            }
            return out.toString();
        } catch (IOException e) {
            return "ERROR: 读取失败: " + e.getMessage();
        }
    }

    /** 跨文件符号定义搜索：在工作区或指定子目录内按名称/类型/glob 检索类、接口、方法与函数定义。 */
    public String search(String query, String scopePath, String glob, String kindFilter) {
        if (query == null || query.isBlank()) {
            return "ERROR: 缺少参数 query";
        }
        String needle = query.strip();
        String needleLower = needle.toLowerCase(Locale.ROOT);
        String normalizedKind = normalizeKindFilter(kindFilter);

        List<Path> rootsToScan = new ArrayList<>();
        if (scopePath != null && !scopePath.isBlank()) {
            Path resolved;
            try {
                resolved = paths.resolve(scopePath);
            } catch (RuntimeException e) {
                return "ERROR: " + e.getMessage();
            }
            if (!Files.exists(resolved)) {
                return "ERROR: 路径不存在: " + scopePath;
            }
            rootsToScan.add(resolved);
        } else {
            rootsToScan.addAll(paths.roots());
        }

        GlobFilter globFilter;
        try {
            globFilter = GlobFilter.compile(glob);
        } catch (IllegalArgumentException e) {
            return "ERROR: 无效的 glob 表达式: " + glob;
        }

        List<SearchHit> hits = new ArrayList<>();
        AtomicInteger scannedFiles = new AtomicInteger();
        AtomicBoolean capped = new AtomicBoolean();
        long deadline = System.nanoTime() + SEARCH_TIME_BUDGET_NANOS;

        for (Path base : rootsToScan) {
            if (capped.get()) {
                break;
            }
            if (Files.isRegularFile(base)) {
                scanSingleFile(base, needle, needleLower, normalizedKind, globFilter, hits, scannedFiles);
                continue;
            }
            try {
                Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (System.nanoTime() >= deadline || scannedFiles.get() >= MAX_SCAN_FILES) {
                                capped.set(true);
                                return FileVisitResult.TERMINATE;
                            }
                            if (!dir.equals(base)) {
                                String dirName = dir.getFileName().toString();
                                if (SearchTool.SKIPPED_DIRECTORIES.contains(dirName) || dirName.startsWith(".")
                                    || !paths.allowsDirectory(dir)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (System.nanoTime() >= deadline || scannedFiles.get() >= MAX_SCAN_FILES) {
                                capped.set(true);
                                return FileVisitResult.TERMINATE;
                            }
                            if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.size() > MAX_SCAN_FILE_BYTES
                                || !paths.allows(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            scanSingleFile(file, needle, needleLower, normalizedKind, globFilter, hits, scannedFiles);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
            } catch (IOException ignored) {
                // 忽略个别不可访问目录
            }
        }

        if (hits.isEmpty()) {
            return "未找到匹配符号「" + needle + "」（已扫描 " + scannedFiles.get() + " 个文件"
                + (capped.get() ? "，已达扫描上限，建议指定 path 或 glob 缩小范围" : "") + "）。";
        }
        hits.sort(Comparator.comparingInt(SearchHit::score).reversed().thenComparing(SearchHit::path)
            .thenComparingInt(SearchHit::line));
        boolean truncated = hits.size() > MAX_SEARCH_RESULTS;
        List<SearchHit> shown = truncated ? hits.subList(0, MAX_SEARCH_RESULTS) : hits;
        StringBuilder out = new StringBuilder();
        out.append("符号搜索「").append(needle).append("」（共命中 ").append(hits.size()).append(" 处，已扫描 ")
            .append(scannedFiles.get()).append(" 个文件");
        if (capped.get()) {
            out.append("，已达扫描上限");
        }
        out.append("）：");
        for (SearchHit hit : shown) {
            out.append('\n').append(hit.path()).append(':').append(hit.line()).append(": [").append(hit.kind())
                .append("] ").append(hit.label());
        }
        if (truncated) {
            out.append("\n… 仅显示前 ").append(MAX_SEARCH_RESULTS).append(" 处最相关定义（其余 ")
                .append(hits.size() - MAX_SEARCH_RESULTS).append(" 处已省略，可用 path/glob/kind 进一步过滤）");
        }
        return out.toString();
    }

    private void scanSingleFile(Path file, String needle, String needleLower, String kindFilter, GlobFilter globFilter,
        List<SearchHit> hits, AtomicInteger scannedFiles) {
        String display = paths.display(file);
        String ext = FileNames.extension(display);
        if (globFilter != null) {
            if (!globFilter.matches(display, file.getFileName().toString())) {
                return;
            }
        } else if (!CODE_EXTENSIONS.contains(ext)) {
            return;
        }
        scannedFiles.incrementAndGet();
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_SCAN_FILE_BYTES) {
                return;
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return;
        }
        if (Texts.isBinary(bytes)) {
            return;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        // 大小写不敏感快速预过滤：文件内容完全不含查询子串时跳过正则提取
        if (!containsIgnoreCase(content, needleLower)) {
            return;
        }
        String[] lines = content.split("\\R", -1);
        for (Symbol symbol : extract(ext, lines)) {
            if (!matchesKind(symbol.kind(), kindFilter)) {
                continue;
            }
            int score = scoreMatch(symbol, needle, needleLower);
            if (score > 0) {
                hits.add(new SearchHit(display, symbol.line(), symbol.kind(), symbol.label(), score));
            }
        }
    }

    private static boolean containsIgnoreCase(String text, String lowerNeedle) {
        int len = lowerNeedle.length();
        int max = text.length() - len;
        if (max < 0) {
            return false;
        }
        char first = lowerNeedle.charAt(0);
        char firstUpper = Character.toUpperCase(first);
        for (int i = 0; i <= max; i++) {
            char c = text.charAt(i);
            if ((c == first || c == firstUpper) && text.regionMatches(true, i, lowerNeedle, 0, len)) {
                return true;
            }
        }
        return false;
    }

    private static int scoreMatch(Symbol symbol, String needle, String needleLower) {
        String name = symbol.name() == null ? "" : symbol.name();
        String nameLower = name.toLowerCase(Locale.ROOT);
        int typeBonus = isTypeKind(symbol.kind()) ? 5 : 0;
        if (name.equals(needle)) {
            return 100 + typeBonus;
        }
        if (nameLower.equals(needleLower)) {
            return 90 + typeBonus;
        }
        if (nameLower.startsWith(needleLower)) {
            return 70 + typeBonus;
        }
        if (nameLower.contains(needleLower)) {
            return 50 + typeBonus;
        }
        if (symbol.label().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return 20 + typeBonus;
        }
        return 0;
    }

    private static boolean isTypeKind(String kind) {
        return "class".equals(kind) || "interface".equals(kind) || "record".equals(kind) || "enum".equals(kind)
            || "struct".equals(kind) || "trait".equals(kind) || "type".equals(kind);
    }

    private static String normalizeKindFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesKind(String symbolKind, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        if (symbolKind.equals(filter)) {
            return true;
        }
        if ("func".equals(filter) || "fn".equals(filter) || "def".equals(filter) || "function".equals(filter)
            || "method".equals(filter)) {
            return "function".equals(symbolKind) || "method".equals(symbolKind);
        }
        if ("type".equals(filter)) {
            return isTypeKind(symbolKind);
        }
        return false;
    }

    private static List<Symbol> extract(String ext, String[] lines) {
        return switch (ext) {
            case "java" -> extractJava(lines);
            case "kt", "kts" -> extractKotlin(lines);
            case "ts", "tsx", "js", "jsx", "mjs", "cjs" -> extractTypeScript(lines);
            case "py" -> extractPython(lines);
            case "go" -> extractGo(lines);
            case "rs" -> extractRust(lines);
            case "md", "markdown" -> extractMarkdown(lines);
            default -> extractGeneric(lines);
        };
    }

    private static List<Symbol> extractJava(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        scanCStyleLines(lines, (i, raw) -> {
            String trimmed = raw.strip();
            if (trimmed.startsWith("@") || trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                return;
            }
            Matcher typeMatch = JAVA_TYPE.matcher(raw);
            if (typeMatch.find()) {
                String kind = typeMatch.group(1).replace("@", "");
                String name = typeMatch.group(2);
                symbols.add(new Symbol(i + 1, name, kind, clipSignature(trimmed), leadingSpaces(raw) >= 4));
                return;
            }
            Symbol method = matchJavaMethod(lines, i, raw, trimmed);
            if (method != null) {
                symbols.add(method);
            }
        });
        return symbols;
    }

    /** 跳过 C 风格块注释、单行注释与空白行，向回调传递非注释代码行的索引与原文。 */
    private static void scanCStyleLines(String[] lines, BiConsumer<Integer, String> visitor) {
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            visitor.accept(i, raw);
        }
    }

    private static Symbol matchJavaMethod(String[] lines, int index, String raw, String trimmed) {
        // 方法签名里不会在 '(' 前出现 '='，排除字段初始化与 lambda 赋值
        int paren = trimmed.indexOf('(');
        int eq = trimmed.indexOf('=');
        if (paren < 0 || (eq >= 0 && eq < paren)) {
            return null;
        }
        String candidate = joinJavaSignature(lines, index, raw, trimmed);
        Matcher methodMatch = JAVA_METHOD.matcher(candidate);
        if (!methodMatch.find()) {
            return null;
        }
        String name = methodMatch.group(1);
        if (CONTROL_KEYWORDS.contains(name)) {
            return null;
        }
        // 排除普通语句调用：方法声明前必须有返回类型、修饰符，或是大写开头的构造器
        String beforeName = trimmed.substring(0, trimmed.indexOf(name)).strip();
        if ((beforeName.isEmpty() && !Character.isUpperCase(name.charAt(0))) || beforeName.endsWith(".")
            || beforeName.endsWith("->")) {
            return null;
        }
        return new Symbol(index + 1, name, "method", clipSignature(trimTrailingBrace(candidate.strip())), true);
    }

    private static String joinJavaSignature(String[] lines, int index, String raw, String trimmed) {
        if (trimmed.endsWith("{") || trimmed.endsWith(";")) {
            return raw;
        }
        // 多行方法签名把后续最多 2 行拼在一起识别 '{' 或 ';'
        StringBuilder joined = new StringBuilder(raw);
        for (int next = index + 1; next < Math.min(lines.length, index + 3); next++) {
            joined.append(' ').append(lines[next].strip());
            if (lines[next].contains("{") || lines[next].contains(";")) {
                break;
            }
        }
        return joined.toString();
    }

    private static List<Symbol> extractKotlin(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            Matcher matcher = KT_DECL.matcher(raw);
            if (matcher.find()) {
                String kw = matcher.group(1);
                String name = matcher.group(2);
                String kind = "fun".equals(kw) ? "function" : ("typealias".equals(kw) ? "type" : kw);
                boolean nested = "fun".equals(kw) && leadingSpaces(raw) >= 2;
                symbols.add(new Symbol(i + 1, name, kind, clipSignature(trimTrailingBrace(raw.strip())), nested));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractTypeScript(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        scanCStyleLines(lines, (i, raw) -> {
            String trimmed = raw.strip();
            Matcher typeMatch = TS_TYPE.matcher(raw);
            if (typeMatch.find()) {
                symbols.add(new Symbol(i + 1, typeMatch.group(2), typeMatch.group(1),
                    clipSignature(trimTrailingBrace(trimmed)), false));
                return;
            }
            Matcher fnMatch = TS_FUNCTION.matcher(raw);
            if (fnMatch.find()) {
                symbols.add(new Symbol(i + 1, fnMatch.group(1), "function", clipSignature(trimTrailingBrace(trimmed)),
                    leadingSpaces(raw) >= 2));
                return;
            }
            Matcher arrowMatch = TS_ARROW.matcher(raw);
            if (arrowMatch.find()) {
                symbols.add(new Symbol(i + 1, arrowMatch.group(1), "function",
                    clipSignature(trimTrailingBrace(trimmed)), leadingSpaces(raw) >= 2));
                return;
            }
            Matcher method = TS_METHOD.matcher(raw);
            if (method.find()) {
                String name = method.group(1);
                if (!CONTROL_KEYWORDS.contains(name)) {
                    symbols.add(new Symbol(i + 1, name, "method", clipSignature(trimTrailingBrace(trimmed)), true));
                }
            }
        });
        return symbols;
    }

    private static List<Symbol> extractPython(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = PYTHON_DECL.matcher(lines[i]);
            if (matcher.find()) {
                boolean nested = !matcher.group(1).isEmpty();
                String kind = "class".equals(matcher.group(2)) ? "class" : (nested ? "method" : "function");
                symbols.add(new Symbol(i + 1, matcher.group(3), kind, clipSignature(lines[i].strip()), nested));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractGo(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            Matcher typeMatch = GO_TYPE.matcher(raw);
            if (typeMatch.find()) {
                String kind = "struct".equals(typeMatch.group(2)) || "interface".equals(typeMatch.group(2))
                    ? typeMatch.group(2) : "type";
                symbols.add(
                    new Symbol(i + 1, typeMatch.group(1), kind, clipSignature(trimTrailingBrace(raw.strip())), false));
                continue;
            }
            Matcher fn = GO_FUNC.matcher(raw);
            if (fn.find()) {
                boolean method = fn.group(1) != null;
                symbols.add(new Symbol(i + 1, fn.group(2), method ? "method" : "function",
                    clipSignature(trimTrailingBrace(raw.strip())), method));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractRust(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            Matcher typeMatch = RUST_TYPE.matcher(raw);
            if (typeMatch.find()) {
                symbols.add(new Symbol(i + 1, typeMatch.group(2), typeMatch.group(1),
                    clipSignature(trimTrailingBrace(raw.strip())), false));
                continue;
            }
            Matcher fnMatch = RUST_FN.matcher(raw);
            if (fnMatch.find()) {
                boolean nested = leadingSpaces(raw) >= 2;
                symbols.add(new Symbol(i + 1, fnMatch.group(1), nested ? "method" : "function",
                    clipSignature(trimTrailingBrace(raw.strip())), nested));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractMarkdown(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher heading = MD_HEADING.matcher(trimmed);
            if (heading.find()) {
                String title = heading.group(2);
                symbols.add(
                    new Symbol(i + 1, title, "heading", heading.group(1) + " " + title, heading.group(1).length() > 1));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractGeneric(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = GENERIC_DECL.matcher(lines[i]);
            if (matcher.find()) {
                String kw = matcher.group(1);
                String kind = ("func".equals(kw) || "def".equals(kw) || "fn".equals(kw)) ? "function" : kw;
                symbols.add(new Symbol(i + 1, matcher.group(2), kind, kw + " " + matcher.group(2),
                    leadingSpaces(lines[i]) >= 2));
            }
        }
        return symbols;
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length()) {
            char ch = line.charAt(count);
            if (ch == ' ') {
                count++;
            } else if (ch == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }

    private static String trimTrailingBrace(String text) {
        String result = text;
        if (result.endsWith("{")) {
            result = result.substring(0, result.length() - 1).stripTrailing();
        }
        return result;
    }

    private static String clipSignature(String text) {
        return Texts.oneLine(text, MAX_SIGNATURE_CHARS);
    }

    private record Symbol(int line, String name, String kind, String label, boolean nested) {
    }

    private record SearchHit(String path, int line, String kind, String label, int score) {
    }

    private record GlobFilter(PathMatcher fullMatcher, PathMatcher fileNameMatcher) {
        static GlobFilter compile(String rawGlob) {
            if (rawGlob == null || rawGlob.isBlank()) {
                return null;
            }
            String normalized = rawGlob.strip().replace('\\', '/');
            PathMatcher full = FileSystems.getDefault().getPathMatcher("glob:" + normalized);
            return new GlobFilter(full, normalized.contains("/") ? null : full);
        }

        boolean matches(String relativePath, String fileName) {
            String slashPath = relativePath.replace('\\', '/');
            if (fileNameMatcher != null && fileNameMatcher.matches(Path.of(fileName))) {
                return true;
            }
            return fullMatcher.matches(Path.of(slashPath));
        }
    }
}
