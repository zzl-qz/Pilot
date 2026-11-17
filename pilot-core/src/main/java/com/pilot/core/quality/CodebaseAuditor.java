package com.pilot.core.quality;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.pilot.core.quality.CodeQualityCheck.Finding;
import com.pilot.core.quality.CodeTokenizer.ParsedLine;
import com.pilot.core.util.FileNames;
import com.pilot.core.workspace.PathValidator;

/**
 * 存量代码全量静态审计引擎。
 * 有界扫描目标范围内的生产源码，汇总重复克隆、魔法字面量、结构臃肿、性能陷阱与边界隐患，并支持前后快照对比。
 */
public final class CodebaseAuditor {
    public static final int MAX_VISITED_FILES = 5_000;
    public static final int MAX_ANALYZED_FILES = 250;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int PER_CATEGORY_LIMIT = 12;
    private static final int MAX_HOTSPOTS = 10;
    private static final int MAX_EXTERNAL_PEERS = 32;

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".pilot", ".idea", ".vscode", ".gradle", ".venv",
        "venv", "__pycache__", "node_modules", "target", "build", "dist", "out", "coverage", "test", "tests",
        "__tests__", "spec", "specs", "fixture", "fixtures");

    private CodebaseAuditor() {
    }

    public enum Category {
        DUPLICATES("duplicates", "重复与造轮子", 1), PERFORMANCE("performance", "性能隐患", 0), BOUNDARY("boundary", "边界与资源", 0),
        STRUCTURE("structure", "结构臃肿", 2), MAGIC("magic", "魔法字面量", 3);

        private final String focusKey;
        private final String label;
        private final int severityOrder;

        Category(String focusKey, String label, int severityOrder) {
            this.focusKey = focusKey;
            this.label = label;
            this.severityOrder = severityOrder;
        }

        public String focusKey() {
            return focusKey;
        }

        public String label() {
            return label;
        }

        static Category ofKind(String kind) {
            if (PerformanceBoundaryScanner.isPerformanceKind(kind)) {
                return PERFORMANCE;
            }
            if (PerformanceBoundaryScanner.isBoundaryKind(kind) || "empty-catch".equals(kind)) {
                return BOUNDARY;
            }
            return switch (kind) {
                case "duplicate-block", "structural-clone", "bypass-helper" -> DUPLICATES;
                case "magic-number", "magic-status", "repeated-literal" -> MAGIC;
                default -> STRUCTURE;
            };
        }
    }

    public record AuditItem(String path, int line, Category category, String kind, String message) {
    }

    public record Hotspot(String path, int codeLines, int issueCount) {
    }

    public record AuditSnapshot(int scannedFiles, int totalCodeLines, int duplicateBlocks, int magicLiterals,
        int structureSmells, int perfHazards, int boundaryHazards, boolean truncated, List<Hotspot> hotspots,
        List<AuditItem> items) {

        public static final AuditSnapshot EMPTY = new AuditSnapshot(0, 0, 0, 0, 0, 0, 0, false, List.of(), List.of());

        public int totalIssues() {
            return duplicateBlocks + magicLiterals + structureSmells + perfHazards + boundaryHazards;
        }

        public List<AuditItem> filterFocus(String focus) {
            String key = focus == null || focus.isBlank() ? "all" : focus.strip().toLowerCase(Locale.ROOT);
            return "all".equals(key) ? items
                : items.stream().filter(item -> item.category().focusKey().equals(key)).toList();
        }

        public String renderReport(String scopeLabel, String focus, int maxFindings) {
            int limit = Math.max(1, Math.min(100, maxFindings));
            String normFocus = focus == null || focus.isBlank() ? "all" : focus.strip().toLowerCase(Locale.ROOT);
            StringBuilder out = new StringBuilder("[存量代码审计账本] 范围: ").append(scopeLabel).append(" | 焦点: ")
                .append(normFocus).append("\n- 扫描生产文件: ").append(scannedFiles)
                .append(truncated ? "（已达安全上限截断，可指定子目录细查）" : "").append(" | 有效代码行: ").append(totalCodeLines)
                .append("\n- 问题汇总: 总计 ").append(totalIssues()).append(" 处（重复块 ").append(duplicateBlocks)
                .append(" | 性能隐患 ").append(perfHazards).append(" | 边界与资源 ").append(boundaryHazards).append(" | 结构臃肿 ")
                .append(structureSmells).append(" | 魔法字面量 ").append(magicLiterals).append("）");
            if (!hotspots.isEmpty()) {
                out.append("\n\n优先整治热点文件：");
                hotspots.forEach(h -> out.append("\n- ").append(h.path()).append("（问题 ").append(h.issueCount())
                    .append(" 处，有效行 ").append(h.codeLines()).append("）"));
            }
            List<AuditItem> filtered = filterFocus(normFocus);
            if (filtered.isEmpty()) {
                return out.append("\n\n当前焦点下未检出待整治问题。").toString();
            }
            out.append("\n\n具体问题定位（显示前 ").append(Math.min(limit, filtered.size())).append('/').append(filtered.size())
                .append(" 条）：");
            for (int i = 0; i < Math.min(limit, filtered.size()); i++) {
                AuditItem item = filtered.get(i);
                out.append("\n- ").append(item.path()).append(':').append(item.line()).append(" [").append(item.kind())
                    .append("] ").append(item.message());
            }
            return out.toString();
        }
    }

    public record DeltaReport(AuditSnapshot before, AuditSnapshot after, int codeLinesDelta, int totalIssuesBefore,
        int totalIssuesAfter, int duplicatesDelta, int magicDelta, int structureDelta, int perfDelta, int boundaryDelta,
        boolean improved) {

        public int totalIssuesDelta() {
            return totalIssuesAfter - totalIssuesBefore;
        }

        public boolean regressed() {
            return totalIssuesAfter > totalIssuesBefore;
        }

        public String renderSummary() {
            return "代码有效行 " + before.totalCodeLines() + " -> " + after.totalCodeLines() + " (" + signed(codeLinesDelta)
                + ") | 总问题数 " + totalIssuesBefore + " -> " + totalIssuesAfter + " (" + signed(totalIssuesDelta())
                + ") [重复 " + signed(duplicatesDelta) + ", 性能 " + signed(perfDelta) + ", 边界 " + signed(boundaryDelta)
                + ", 结构 " + signed(structureDelta) + ", 魔法值 " + signed(magicDelta) + "]";
        }

        private static String signed(int delta) {
            return delta > 0 ? "+" + delta : String.valueOf(delta);
        }
    }

    public static DeltaReport compare(AuditSnapshot baseline, AuditSnapshot current) {
        AuditSnapshot base = baseline == null ? AuditSnapshot.EMPTY : baseline;
        AuditSnapshot cur = current == null ? AuditSnapshot.EMPTY : current;
        int linesDelta = cur.totalCodeLines() - base.totalCodeLines();
        int beforeTotal = base.totalIssues();
        int afterTotal = cur.totalIssues();
        boolean improved = afterTotal < beforeTotal || (beforeTotal == 0 && afterTotal == 0 && linesDelta < 0);
        return new DeltaReport(base, cur, linesDelta, beforeTotal, afterTotal,
            cur.duplicateBlocks() - base.duplicateBlocks(), cur.magicLiterals() - base.magicLiterals(),
            cur.structureSmells() - base.structureSmells(), cur.perfHazards() - base.perfHazards(),
            cur.boundaryHazards() - base.boundaryHazards(), improved);
    }

    public static AuditSnapshot audit(Path workspaceRoot) {
        return audit(workspaceRoot, ".");
    }

    public static AuditSnapshot audit(Path workspaceRoot, String relativePath) {
        return workspaceRoot == null || !Files.isDirectory(workspaceRoot) ? AuditSnapshot.EMPTY
            : audit(new PathValidator(workspaceRoot), relativePath);
    }

    public static AuditSnapshot audit(PathValidator validator, String relativePath) {
        if (validator == null) {
            return AuditSnapshot.EMPTY;
        }
        Path target = validator.resolve(relativePath == null || relativePath.isBlank() ? "." : relativePath.strip());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            String rel = validator.display(target).replace('\\', '/');
            String text = validator.allows(target) ? WorkspaceTexts.readRegularFile(target, MAX_FILE_BYTES) : null;
            return text == null || text.isBlank() || !CodeQualityCheck.applies(rel) ? AuditSnapshot.EMPTY
                : auditTexts(validator.root(), Map.of(rel, text));
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return AuditSnapshot.EMPTY;
        }
        Map<String, Path> found = new TreeMap<>();
        boolean hitVisitCap = collectFiles(validator, target, found);
        Map<String, String> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : found.entrySet()) {
            if (loaded.size() >= MAX_ANALYZED_FILES) {
                break;
            }
            String text = WorkspaceTexts.readRegularFile(entry.getValue(), MAX_FILE_BYTES);
            if (text != null && !text.isBlank()) {
                loaded.put(entry.getKey(), text);
            }
        }
        return analyzeTexts(CodeAssetIndex.scan(validator.root()), loaded, Map.of(),
            hitVisitCap || found.size() > MAX_ANALYZED_FILES);
    }

    public static AuditSnapshot auditTexts(Path workspaceRoot, Map<String, String> fileTexts) {
        if (fileTexts == null || fileTexts.isEmpty()) {
            return AuditSnapshot.EMPTY;
        }
        Map<String, String> sorted = new TreeMap<>();
        Set<String> inBatch = new HashSet<>();
        fileTexts.forEach((rawPath, text) -> {
            if (rawPath != null && text != null && !text.isBlank()) {
                String norm = rawPath.replace('\\', '/');
                inBatch.add(norm);
                if (CodeQualityCheck.applies(norm)) {
                    sorted.put(norm, text);
                }
            }
        });
        if (sorted.isEmpty()) {
            return AuditSnapshot.EMPTY;
        }
        Map<String, Map<String, List<ParsedLine>>> externalPeers = new LinkedHashMap<>();
        if (workspaceRoot != null && Files.isDirectory(workspaceRoot)) {
            for (String path : sorted.keySet()) {
                Map<String, List<ParsedLine>> bucket =
                    externalPeers.computeIfAbsent(FileNames.extension(path), ignored -> new LinkedHashMap<>());
                if (bucket.size() < MAX_EXTERNAL_PEERS) {
                    CloneDetector.loadSiblings(workspaceRoot, path).forEach((peerPath, lines) -> {
                        if (!inBatch.contains(peerPath)) {
                            bucket.putIfAbsent(peerPath, lines);
                        }
                    });
                }
            }
        }
        return analyzeTexts(workspaceRoot == null ? CodeAssetIndex.Snapshot.EMPTY : CodeAssetIndex.scan(workspaceRoot),
            sorted, externalPeers, false);
    }

    private static AuditSnapshot analyzeTexts(CodeAssetIndex.Snapshot assets, Map<String, String> sortedFiles,
        Map<String, Map<String, List<ParsedLine>>> seedPeersByExt, boolean truncated) {
        if (sortedFiles.isEmpty()) {
            return AuditSnapshot.EMPTY;
        }
        Map<String, Map<String, List<ParsedLine>>> peersByExt = new LinkedHashMap<>();
        seedPeersByExt.forEach((ext, map) -> peersByExt.put(ext, new LinkedHashMap<>(map)));
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        int totalCodeLines = 0;
        List<Hotspot> hotspots = new ArrayList<>();
        List<AuditItem> allItems = new ArrayList<>();

        for (Map.Entry<String, String> entry : sortedFiles.entrySet()) {
            String path = entry.getKey();
            String ext = FileNames.extension(path);
            List<ParsedLine> lines = CodeTokenizer.tokenize(ext, entry.getValue());
            int codeLines = (int)lines.stream().filter(l -> !l.isEmptyCode()).count();
            totalCodeLines += codeLines;
            Map<String, List<ParsedLine>> peers = peersByExt.computeIfAbsent(ext, ignored -> new LinkedHashMap<>());
            List<Finding> findings =
                CodeQualityCheck.inspectFullFile(assets, peers, path, ext, lines, entry.getValue(), PER_CATEGORY_LIMIT);
            peers.put(path, lines);
            if (!findings.isEmpty()) {
                hotspots.add(new Hotspot(path, codeLines, findings.size()));
                for (Finding f : findings) {
                    Category cat = Category.ofKind(f.kind());
                    counts.merge(cat, 1, Integer::sum);
                    allItems.add(new AuditItem(path, f.line(), cat, f.kind(), f.message()));
                }
            }
        }
        hotspots.sort(Comparator.comparingInt(Hotspot::issueCount).reversed()
            .thenComparing(Comparator.comparingInt(Hotspot::codeLines).reversed()).thenComparing(Hotspot::path));
        allItems.sort(Comparator.comparingInt((AuditItem item) -> item.category().severityOrder)
            .thenComparing(AuditItem::path).thenComparingInt(AuditItem::line));
        return new AuditSnapshot(sortedFiles.size(), totalCodeLines, counts.getOrDefault(Category.DUPLICATES, 0),
            counts.getOrDefault(Category.MAGIC, 0), counts.getOrDefault(Category.STRUCTURE, 0),
            counts.getOrDefault(Category.PERFORMANCE, 0), counts.getOrDefault(Category.BOUNDARY, 0), truncated,
            hotspots.size() <= MAX_HOTSPOTS ? List.copyOf(hotspots) : List.copyOf(hotspots.subList(0, MAX_HOTSPOTS)),
            List.copyOf(allItems));
    }

    private static boolean collectFiles(PathValidator validator, Path base, Map<String, Path> sorted) {
        boolean[] capped = {false};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                private int visited;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (visited >= MAX_VISITED_FILES) {
                        capped[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String name =
                        dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    return !dir.equals(base) && (SKIPPED_DIRS.contains(name) || !validator.allowsDirectory(dir))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited > MAX_VISITED_FILES) {
                        capped[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String rel = validator.display(file).replace('\\', '/');
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink() && attrs.size() > 0
                        && attrs.size() <= MAX_FILE_BYTES && validator.allows(file) && CodeQualityCheck.applies(rel)) {
                        sorted.put(rel, file);
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
        return capped[0];
    }
}
