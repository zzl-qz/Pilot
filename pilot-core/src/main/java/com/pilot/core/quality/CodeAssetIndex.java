package com.pilot.core.quality;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.util.FileNames;
import com.pilot.core.util.Texts;

/**
 * 多语言公共代码资产索引（带短周期有界缓存与符号链接防护）。
 * 覆盖 Java/Kotlin/TS/JS/Python/Go/Rust 的公共工具、枚举与具名常量。
 */
public final class CodeAssetIndex {
    private static final int MAX_FILES = 40;
    private static final int MAX_LINES = 60;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_VISITED_FILES = 12_000;
    private static final long CACHE_TTL_NANOS = 5_000_000_000L;
    private static final int MAX_CACHE_ENTRIES = 16;

    private static final ConcurrentHashMap<Path, CachedSnapshot> CACHE = new ConcurrentHashMap<>();

    private static final Set<String> SUPPORTED_EXTS =
        Set.of("java", "kt", "ts", "tsx", "js", "jsx", "mjs", "py", "go", "rs");
    private static final Set<String> SHARED_DIRS = Set.of("common", "commons", "shared", "util", "utils", "helper",
        "helpers", "constant", "constants", "enum", "enums", "support", "hooks", "lib");
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".idea", ".vscode", ".gradle", ".pilot", ".venv",
        "venv", "__pycache__", "target", "build", "node_modules", "out", "dist", "coverage", "test", "tests",
        "__tests__", "fixtures", "evaluation", "docs", "docs-public");
    private static final Pattern SHARED_STEM = Pattern.compile(
        "(?:.*(?:Util|Utils|Helper|Helpers|Constant|Constants|Enum|Enums|Support|Texts|FileNames|Sha256|Paths|Codec)"
            + "|use[A-Z][A-Za-z0-9_]+)$");

    private static final Pattern TYPE_DECL = Pattern
        .compile("^\\s*(?:export\\s+)?(?:public\\s+)?" + "(?:final\\s+|abstract\\s+|open\\s+|data\\s+|sealed\\s+)?"
            + "(?:class|interface|enum|object|record|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern METHOD_DECL = Pattern.compile(
        "^\\s*(?:export\\s+(?:async\\s+)?function|(?:public|protected)\\s+(?:static\\s+)?(?:final\\s+)?[A-Za-z0-9_<>,?\\[\\]\\s]+|"
            + "pub\\s+(?:async\\s+)?fn|func|def)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^;{}]{0,120})\\)");
    private static final Pattern TS_ARROW = Pattern
        .compile("^\\s*export\\s+const\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*=\\s*(?:async\\s*)?\\(([^;{}]{0,120})\\)\\s*=>");
    private static final Pattern NAMED_CONST =
        Pattern.compile("^\\s*(?:(?:public|protected|export|pub)\\s+)*(?:static\\s+final\\s+[A-Za-z0-9_<>.\\[\\]]+\\s+|"
            + "const\\s+|readonly\\s+)([A-Z][A-Z0-9_]{1,})\\s*(?::[^=]+)?=\\s*([^;\\n]+)");
    private static final Pattern ENUM_ITEM =
        Pattern.compile("^\\s*([A-Z][A-Z0-9_]{2,})(?:\\s*\\(([^)]*)\\)|\\s*=\\s*([^,;]+))?\\s*[,;]?\\s*$");
    private static final Pattern QUOTED = Pattern.compile("^[\"']([^\"']+)[\"']$");
    private static final Pattern NUMBER = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)[LFDlfd]?$");

    private CodeAssetIndex() {
    }

    public record ConstantRef(String symbol, String path, String value) {
    }

    private record CachedSnapshot(Snapshot snapshot, long expiresAtNanos) {
    }

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Map.of(), Map.of());

        private final List<Asset> assets;
        private final Map<String, List<ConstantRef>> byValue;
        private final Map<String, List<ConstantRef>> byName;
        private final Map<String, ConstantRef> capabilities;

        Snapshot(List<Asset> assets, Map<String, List<ConstantRef>> byValue, Map<String, List<ConstantRef>> byName,
            Map<String, ConstantRef> capabilities) {
            this.assets = List.copyOf(assets);
            this.byValue = freeze(byValue);
            this.byName = freeze(byName);
            this.capabilities = Map.copyOf(capabilities);
        }

        private static Map<String, List<ConstantRef>> freeze(Map<String, List<ConstantRef>> src) {
            Map<String, List<ConstantRef>> out = new LinkedHashMap<>();
            src.forEach((k, v) -> out.put(k, List.copyOf(v)));
            return Map.copyOf(out);
        }

        public boolean isEmpty() {
            return assets.isEmpty();
        }

        public List<ConstantRef> candidatesFor(String literal) {
            if (literal == null || literal.isBlank()) {
                return List.of();
            }
            Set<ConstantRef> merged = new LinkedHashSet<>();
            String trimmed = literal.strip();
            merged.addAll(byValue.getOrDefault(trimmed, List.of()));
            merged.addAll(byName.getOrDefault(trimmed.toUpperCase(Locale.ROOT).replace('-', '_'), List.of()));
            return List.copyOf(merged);
        }

        public String adviseConstant(String literal, boolean numeric, String currentPath, String rawLine,
            Set<String> importedWords, Set<String> importedPaths) {
            return ConstantAffinityMatcher.advise(candidatesFor(literal), ConstantAffinityMatcher.MatchContext
                .of(literal, numeric, currentPath, rawLine, importedWords, importedPaths));
        }

        public ConstantRef recommendConstant(String literal, String currentPath) {
            return recommendConstant(literal, currentPath, "");
        }

        public ConstantRef recommendConstant(String literal, String currentPath, String rawLine) {
            List<ConstantRef> list = candidatesFor(literal);
            if (list.isEmpty()) {
                return null;
            }
            boolean numeric = literal.strip().matches("-?\\d+(?:\\.\\d+)?");
            String advice = ConstantAffinityMatcher.advise(list,
                ConstantAffinityMatcher.MatchContext.of(literal, numeric, currentPath, rawLine, Set.of(), Set.of()));
            if (!advice.startsWith("同链路已有可复用定义 ")) {
                return null;
            }
            return list.stream().filter(ref -> advice.contains(ref.symbol() + "（")).findFirst().orElse(null);
        }

        public String detectBypass(String currentPath, String codeOnly, String rawLine) {
            String path = currentPath == null ? "" : currentPath.replace('\\', '/');
            ConstantRef sha = capabilities.get("sha256");
            if (sha != null && !path.equals(sha.path()) && (rawLine.contains("MessageDigest.getInstance(\"SHA-256\")")
                || rawLine.contains("createHash('sha256')") || rawLine.contains("createHash(\"sha256\")"))) {
                return "手写 SHA-256 摘要；仓库已有公共工具 " + sha.symbol() + "（" + sha.path() + "），请直接复用";
            }
            ConstantRef ext = capabilities.get("file-extension");
            if (ext != null && !path.equals(ext.path()) && codeOnly.contains("lastIndexOf('.')")
                && codeOnly.contains("substring(")) {
                return "手写文件名后缀截取；仓库已有公共工具 " + ext.symbol() + "（" + ext.path() + "），请直接复用";
            }
            ConstantRef text = capabilities.get("texts-oneline");
            if (text != null && !path.equals(text.path()) && rawLine.contains("replaceAll(\"\\\\s+\"")
                && codeOnly.contains(".strip()")) {
                return "手写单行空白折叠截断；仓库已有公共工具 " + text.symbol() + "（" + text.path() + "），请直接复用";
            }
            ConstantRef json = capabilities.get("json");
            if (json != null && !path.equals(json.path())
                && (codeOnly.contains("new ObjectMapper(") || codeOnly.contains("new Gson("))) {
                return "在业务类中新建 JSON 序列化器；仓库已有公共封装 " + json.symbol() + "（" + json.path() + "），优先复用";
            }
            ConstantRef date = capabilities.get("date-format");
            if (date != null && !path.equals(date.path()) && codeOnly.contains("new SimpleDateFormat(")) {
                return "直接实例化非线程安全的 SimpleDateFormat；仓库已有日期工具 " + date.symbol() + "（" + date.path() + "），请优先复用";
            }
            return null;
        }

        public String render() {
            if (assets.isEmpty()) {
                return "";
            }
            List<Asset> sorted = new ArrayList<>(assets);
            sorted.sort(Comparator.comparingInt(Asset::priority).thenComparing(Asset::path));
            List<String> lines = new ArrayList<>();
            for (Asset asset : sorted) {
                if (lines.size() >= MAX_LINES) {
                    break;
                }
                String members = asset.members().isEmpty() ? "" : "；" + String.join("、", asset.members());
                lines.add(Texts.oneLine("- " + asset.path() + "：" + asset.type() + members, 240));
            }
            if (sorted.size() > lines.size()) {
                lines.add("- …其余公共资产已省略，写新工具或常量前先用 search_text / glob_files 查证");
            }
            return String.join("\n", lines);
        }
    }

    public static String summarize(Path root) {
        return scan(root).render();
    }

    public static Snapshot scan(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Snapshot.EMPTY;
        }
        Path key = root.toAbsolutePath().normalize();
        long now = System.nanoTime();
        CachedSnapshot cached = CACHE.get(key);
        if (cached != null && now < cached.expiresAtNanos()) {
            return cached.snapshot();
        }
        Snapshot fresh = scanUncached(key);
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(key, new CachedSnapshot(fresh, now + CACHE_TTL_NANOS));
        return fresh;
    }

    private static Snapshot scanUncached(Path root) {
        List<Asset> assets = new ArrayList<>();
        Map<String, List<ConstantRef>> byValue = new LinkedHashMap<>();
        Map<String, List<ConstantRef>> byName = new LinkedHashMap<>();
        Map<String, ConstantRef> capabilities = new LinkedHashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int visited;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (visited > MAX_VISITED_FILES || assets.size() >= MAX_FILES || attrs.isSymbolicLink()) {
                        return attrs.isSymbolicLink() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.TERMINATE;
                    }
                    String name =
                        dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    return !dir.equals(root) && SKIPPED_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited > MAX_VISITED_FILES || assets.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    int priority = attrs.isRegularFile() ? priority(root, file, attrs.size()) : -1;
                    if (priority >= 0) {
                        Asset asset = readAsset(root, file, priority, byValue, byName, capabilities);
                        if (asset != null) {
                            assets.add(asset);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) {
        }
        return assets.isEmpty() ? Snapshot.EMPTY : new Snapshot(assets, byValue, byName, capabilities);
    }

    private static int priority(Path root, Path file, long size) {
        if (size <= 0 || size > MAX_FILE_BYTES) {
            return -1;
        }
        String name = file.getFileName().toString();
        if (!SUPPORTED_EXTS.contains(FileNames.extension(name)) || CodeQualityCheck.isTestFile(name)
            || name.endsWith(".min.js") || name.endsWith(".d.ts")) {
            return -1;
        }
        String stem = name.substring(0, name.lastIndexOf('.'));
        if (SHARED_STEM.matcher(stem).matches()) {
            return 0;
        }
        Path rel = root.relativize(file);
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            String dir = rel.getName(i).toString().toLowerCase(Locale.ROOT);
            if (SKIPPED_DIRS.contains(dir)) {
                return -1;
            }
            if (SHARED_DIRS.contains(dir)) {
                return 1;
            }
        }
        return -1;
    }

    private static Asset readAsset(Path root, Path file, int priority, Map<String, List<ConstantRef>> byValue,
        Map<String, List<ConstantRef>> byName, Map<String, ConstantRef> capabilities) {
        String text = WorkspaceTexts.readRegularFile(file, MAX_FILE_BYTES);
        if (text == null) {
            return null;
        }
        String relPath = root.relativize(file).toString().replace('\\', '/');
        String name = file.getFileName().toString();
        String fallback = name.substring(0, name.lastIndexOf('.'));
        String type = "";
        boolean isEnum = false;
        List<String> members = new ArrayList<>();
        recordCapability(fallback, relPath, text, capabilities);

        for (String line : text.lines().limit(500).toList()) {
            if (line.length() > CodeTokenizer.MAX_LINE_CHARS) {
                continue;
            }
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("//") || stripped.startsWith("*")
                || stripped.startsWith("#")) {
                continue;
            }
            if (type.isEmpty()) {
                Matcher decl = TYPE_DECL.matcher(line);
                if (decl.find()) {
                    type = decl.group(1);
                    isEnum = line.contains("enum ");
                }
            }
            extractMember(stripped, type.isEmpty() ? fallback : type, relPath, isEnum, members, byValue, byName);
        }
        return type.isEmpty() && members.isEmpty() ? null
            : new Asset(relPath, type.isEmpty() ? fallback : type, List.copyOf(members), priority);
    }

    private static void extractMember(String stripped, String owner, String relPath, boolean isEnum,
        List<String> members, Map<String, List<ConstantRef>> byValue, Map<String, List<ConstantRef>> byName) {
        Matcher method = METHOD_DECL.matcher(stripped);
        if (method.find() && !Set.of("if", "for", "while", "switch").contains(method.group(1))) {
            addMember(members, method.group(1) + "(" + Texts.oneLine(method.group(2), 60) + ")");
            return;
        }
        Matcher arrow = TS_ARROW.matcher(stripped);
        if (arrow.find()) {
            addMember(members, arrow.group(1) + "(" + Texts.oneLine(arrow.group(2), 60) + ")");
            return;
        }
        Matcher named = NAMED_CONST.matcher(stripped);
        if (named.find()) {
            registerConst(owner, named.group(1), cleanValue(named.group(2)), relPath, members, byValue, byName);
            return;
        }
        if (isEnum) {
            Matcher item = ENUM_ITEM.matcher(stripped);
            if (item.matches()) {
                String arg = item.group(2) != null ? item.group(2) : item.group(3);
                registerConst(owner, item.group(1), arg == null ? item.group(1) : cleanValue(arg), relPath, members,
                    byValue, byName);
            }
        }
    }

    private static void registerConst(String owner, String name, String val, String path, List<String> members,
        Map<String, List<ConstantRef>> byValue, Map<String, List<ConstantRef>> byName) {
        ConstantRef ref = new ConstantRef(owner + "." + name, path, val);
        addBucket(byName, name, ref);
        if (!val.isEmpty() && val.length() <= 40) {
            addBucket(byValue, val, ref);
        }
        addMember(members, name);
    }

    private static void addBucket(Map<String, List<ConstantRef>> map, String key, ConstantRef ref) {
        List<ConstantRef> list = map.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (list.size() < 8 && !list.contains(ref)) {
            list.add(ref);
        }
    }

    private static void addMember(List<String> members, String item) {
        if (members.size() < 5 && !members.contains(item)) {
            members.add(item);
        }
    }

    private static String cleanValue(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip().replaceAll("[,;]+$", "").strip();
        Matcher q = QUOTED.matcher(s);
        if (q.matches()) {
            return q.group(1);
        }
        Matcher n = NUMBER.matcher(s.replace("_", ""));
        return n.matches() ? n.group(1) : "";
    }

    private static void recordCapability(String stem, String relPath, String text, Map<String, ConstantRef> caps) {
        String lowerStem = stem.toLowerCase(Locale.ROOT);
        String lowerText = text.toLowerCase(Locale.ROOT);
        ConstantRef ref = new ConstantRef(stem, relPath, "");
        if (lowerStem.contains("sha256") || ((lowerStem.contains("sha") || lowerStem.contains("sign")
            || lowerStem.contains("digest") || lowerStem.contains("crypto") || lowerStem.contains("hash"))
            && (lowerText.contains("sha-256") || lowerText.contains("sha256")))) {
            caps.putIfAbsent("sha256", ref);
        }
        if ((lowerStem.contains("file") || lowerStem.contains("path"))
            && (text.contains("extension(") || text.contains("getExtension("))) {
            caps.putIfAbsent("file-extension", ref);
        }
        if ((lowerStem.contains("text") || lowerStem.contains("string"))
            && (text.contains("oneLine(") || text.contains("truncate("))) {
            caps.putIfAbsent("texts-oneline", ref);
        }
        if (lowerStem.contains("json") || lowerStem.contains("jackson")) {
            caps.putIfAbsent("json", ref);
        }
        if ((lowerStem.contains("date") || lowerStem.contains("time"))
            && (lowerText.contains("format") || lowerText.contains("parse"))) {
            caps.putIfAbsent("date-format", ref);
        }
    }

    private record Asset(String path, String type, List<String> members, int priority) {
    }
}
