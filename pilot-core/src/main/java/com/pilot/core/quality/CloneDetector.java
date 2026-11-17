package com.pilot.core.quality;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import com.pilot.core.quality.CodeQualityCheck.Finding;
import com.pilot.core.quality.CodeTokenizer.ParsedLine;
import com.pilot.core.util.FileNames;

/**
 * 同文件与跨文件重复代码块检测器。
 * 统一驱动精确克隆（6 行）与词法骨架同构克隆（7 行），自动跳过纯字段罗列的 DTO 与符号链接。
 */
final class CloneDetector {
    private static final int EXACT_LINES = 6;
    private static final int MAX_SIBLING_FILES = 32;
    private static final int MAX_SIBLING_BYTES = 192 * 1024;
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".pilot", ".idea", ".gradle", "node_modules",
        "target", "build", "dist", "out", "test", "tests", "__tests__");

    private CloneDetector() {
    }

    static List<Finding> detect(String currentPath, List<ParsedLine> lines, boolean[] added,
        Map<String, List<ParsedLine>> peers) {
        List<IndexedLine> meaningful = extractMeaningful(lines, added);
        if (meaningful.size() < EXACT_LINES) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        findings.addAll(scanWindow(currentPath, meaningful, peers, EXACT_LINES, CloneDetector::exactKey,
            "duplicate-block", "重复代码块；确认无现成公共方法后再抽离复用", List.of()));
        if (findings.size() < 2 && meaningful.size() >= CodeQualityCheck.DUPLICATE_BLOCK_LINES) {
            findings.addAll(scanWindow(currentPath, meaningful, peers, CodeQualityCheck.DUPLICATE_BLOCK_LINES,
                CloneDetector::structuralKey, "structural-clone", "同构代码（仅变量名或字面量不同）；若职责相同请合并抽离", findings));
        }
        return findings;
    }

    private static List<Finding> scanWindow(String currentPath, List<IndexedLine> meaningful,
        Map<String, List<ParsedLine>> peers, int windowSize, BiFunction<List<IndexedLine>, Integer, String> keyFn,
        String kind, String suffix, List<Finding> prior) {
        String normCurrent = currentPath == null ? "" : currentPath.replace('\\', '/');
        Map<String, BlockRef> index = new LinkedHashMap<>();

        for (Map.Entry<String, List<ParsedLine>> peer : peers.entrySet()) {
            String peerPath = peer.getKey().replace('\\', '/');
            if (peerPath.equals(normCurrent)) {
                continue;
            }
            List<IndexedLine> peerLines = extractMeaningful(peer.getValue(), null);
            for (int i = 0; i + windowSize <= peerLines.size(); i++) {
                String key = keyFn.apply(peerLines, i);
                if (!key.isEmpty()) {
                    index.putIfAbsent(key, new BlockRef(peerPath, peerLines.get(i).line().lineNumber(), -1));
                }
            }
        }

        List<Finding> out = new ArrayList<>();
        int nextAllowed = 0;
        for (int i = 0; i + windowSize <= meaningful.size() && out.size() + prior.size() < 2; i++) {
            String key = keyFn.apply(meaningful, i);
            if (key.isEmpty()) {
                continue;
            }
            BlockRef hit = index.get(key);
            if (hit == null) {
                index.put(key, new BlockRef(normCurrent, meaningful.get(i).line().lineNumber(), i));
                continue;
            }
            boolean selfOverlap = hit.indexInCurrent() >= 0 && i < hit.indexInCurrent() + windowSize;
            int line = meaningful.get(i).line().lineNumber();
            if (selfOverlap || i < nextAllowed || !hasAdded(meaningful, i, windowSize) || near(line, prior)) {
                continue;
            }
            String where =
                hit.path().equals(normCurrent) ? "第 " + hit.startLine() + " 行附近" : hit.path() + ":" + hit.startLine();
            out.add(new Finding(line, kind, "与 " + where + " 存在 " + windowSize + " 行以上" + suffix));
            nextAllowed = i + windowSize;
        }
        return out;
    }

    static Map<String, List<ParsedLine>> loadSiblings(Path workspaceRoot, String relativePath) {
        if (workspaceRoot == null || relativePath == null || relativePath.isBlank()) {
            return Map.of();
        }
        String normRel = relativePath.replace('\\', '/');
        String ext = FileNames.extension(normRel);
        Path target = workspaceRoot.resolve(normRel).normalize();
        if (!target.startsWith(workspaceRoot)) {
            return Map.of();
        }
        Path dir = target.getParent() != null ? target.getParent() : workspaceRoot;
        if (dir.getParent() != null && dir.getParent().startsWith(workspaceRoot)
            && !dir.getParent().equals(workspaceRoot)) {
            dir = dir.getParent();
        }
        if (!Files.isDirectory(dir)) {
            return Map.of();
        }
        Map<String, List<ParsedLine>> peers = new LinkedHashMap<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (peers.size() >= MAX_SIBLING_FILES || attrs.isSymbolicLink()) {
                        return attrs.isSymbolicLink() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.TERMINATE;
                    }
                    String name = d.getFileName() == null ? "" : d.getFileName().toString().toLowerCase(Locale.ROOT);
                    return SKIPPED_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (peers.size() >= MAX_SIBLING_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile() || attrs.size() <= 0 || attrs.size() > MAX_SIBLING_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = workspaceRoot.relativize(file).toString().replace('\\', '/');
                    if (!rel.equals(normRel) && ext.equals(FileNames.extension(rel)) && CodeQualityCheck.applies(rel)) {
                        String text = WorkspaceTexts.readRegularFile(file, MAX_SIBLING_BYTES);
                        if (text != null) {
                            peers.put(rel, CodeTokenizer.tokenize(ext, text));
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
        return peers;
    }

    private static String exactKey(List<IndexedLine> list, int start) {
        StringBuilder sb = new StringBuilder();
        int logic = 0;
        for (int i = start; i < start + EXACT_LINES; i++) {
            ParsedLine l = list.get(i).line();
            sb.append(l.exactNormalized()).append('\n');
            logic += l.hasLogicOrCall() ? 1 : 0;
        }
        return logic >= 2 && sb.length() >= 80 ? sb.toString() : "";
    }

    private static String structuralKey(List<IndexedLine> list, int start) {
        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        int logic = 0;
        for (int i = start; i < start + CodeQualityCheck.DUPLICATE_BLOCK_LINES; i++) {
            ParsedLine l = list.get(i).line();
            sb.append(l.structuralSkeleton()).append('\n');
            tokens += l.skeletonTokens();
            logic += l.hasLogicOrCall() ? 1 : 0;
        }
        return logic >= 3 && tokens >= 30 ? sb.toString() : "";
    }

    private static boolean hasAdded(List<IndexedLine> list, int start, int size) {
        int n = 0;
        for (int i = start; i < start + size; i++) {
            n += list.get(i).added() ? 1 : 0;
        }
        return n >= 2;
    }

    private static boolean near(int line, List<Finding> prior) {
        return prior.stream().anyMatch(f -> Math.abs(f.line() - line) <= CodeQualityCheck.DUPLICATE_BLOCK_LINES);
    }

    private static List<IndexedLine> extractMeaningful(List<ParsedLine> lines, boolean[] added) {
        List<IndexedLine> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).meaningfulForClone()) {
                out.add(new IndexedLine(lines.get(i), added != null && added[i]));
            }
        }
        return out;
    }

    private record IndexedLine(ParsedLine line, boolean added) {
    }

    private record BlockRef(String path, int startLine, int indexInCurrent) {
    }
}
