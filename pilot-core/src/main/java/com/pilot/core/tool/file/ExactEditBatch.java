package com.pilot.core.tool.file;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 一次验证并应用同一文件里的多个精确替换；任何一处失败都不产生部分修改。 */
final class ExactEditBatch {
    record Edit(String oldText, String newText) {
    }

    record Applied(String content, int count, int oldLines, int newLines) {
    }

    private record Match(int start, int end, String replacement) {
    }

    private ExactEditBatch() {
    }

    static List<Edit> parse(Map<String, Object> args) {
        if (args == null) {
            throw new IllegalArgumentException("缺少编辑参数");
        }
        boolean legacy = args.containsKey("old_string") || args.containsKey("new_string");
        Object batch = args.get("edits");
        if (batch != null && legacy) {
            throw new IllegalArgumentException("edits 与 old_string/new_string 不能同时提供");
        }
        if (batch == null) {
            Object old = args.get("old_string");
            Object replacement = args.get("new_string");
            if (!(old instanceof String text) || text.isEmpty()) {
                throw new IllegalArgumentException("缺少参数 old_string（不能为空）或 edits");
            }
            if (!(replacement instanceof String newText)) {
                throw new IllegalArgumentException("缺少参数 new_string");
            }
            return List.of(new Edit(text, newText));
        }
        if (!(batch instanceof List<?> entries) || entries.isEmpty() || entries.size() > 64) {
            throw new IllegalArgumentException("edits 必须包含 1 到 64 个替换项");
        }
        List<Edit> edits = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof Map<?, ?> item) || !(item.get("old_string") instanceof String old)
                || old.isEmpty() || !(item.get("new_string") instanceof String replacement)) {
                throw new IllegalArgumentException("edits[" + i + "] 需要非空 old_string 和字符串 new_string");
            }
            edits.add(new Edit(old, replacement));
        }
        return List.copyOf(edits);
    }

    static Applied apply(String original, List<Edit> edits) {
        String bom = original.startsWith("\uFEFF") ? "\uFEFF" : "";
        String body = bom.isEmpty() ? original : original.substring(1);
        String eol = body.contains("\r\n") ? "\r\n" : body.contains("\r") ? "\r" : "\n";
        String normalized = normalize(body);
        List<Match> matches = new ArrayList<>(edits.size());
        int oldLines = 0;
        int newLines = 0;
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            String search = normalize(edit.oldText());
            if (search.isEmpty()) {
                throw new IllegalArgumentException("edits[" + i + "] 的 old_string 不能为空");
            }
            int start = normalized.indexOf(search);
            if (start < 0) {
                throw new IllegalArgumentException("edits[" + i + "] 未找到 old_string。最接近的文件片段：\n"
                    + closestExcerpt(normalized, search) + "\n请基于该片段提供更精确的旧内容。");
            }
            if (normalized.indexOf(search, start + search.length()) >= 0) {
                throw new IllegalArgumentException("edits[" + i + "] 的 old_string 匹配到多处，无法唯一定位。请扩大上下文。");
            }
            String replacement = normalize(edit.newText());
            matches.add(new Match(start, start + search.length(), replacement));
            oldLines += (int)search.lines().count();
            newLines += (int)replacement.lines().count();
        }
        matches.sort(Comparator.comparingInt(Match::start));
        for (int i = 1; i < matches.size(); i++) {
            if (matches.get(i).start() < matches.get(i - 1).end()) {
                throw new IllegalArgumentException("edits 包含重叠或嵌套的 old_string；请合并相邻修改");
            }
        }
        StringBuilder updated = new StringBuilder(normalized.length());
        int cursor = 0;
        for (Match match : matches) {
            updated.append(normalized, cursor, match.start()).append(match.replacement());
            cursor = match.end();
        }
        updated.append(normalized, cursor, normalized.length());
        String result = bom + updated.toString().replace("\n", eol);
        return new Applied(result, edits.size(), oldLines, newLines);
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String closestExcerpt(String content, String search) {
        String[] sourceLines = content.split("\\R", -1);
        String[] searchLines = search.split("\\R", -1);
        String needle = searchLines.length == 0 ? search : searchLines[0].strip();
        int bestLine = 0;
        int bestScore = -1;
        for (int i = 0; i < sourceLines.length; i++) {
            String candidate = sourceLines[i].strip();
            int score = commonPrefix(candidate, needle);
            if (!needle.isEmpty() && candidate.contains(needle)) {
                score += needle.length();
            }
            if (score > bestScore) {
                bestScore = score;
                bestLine = i;
            }
        }
        int from = Math.max(0, bestLine - 2);
        int to = Math.min(sourceLines.length, bestLine + 3);
        StringBuilder excerpt = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (excerpt.length() > 0) {
                excerpt.append('\n');
            }
            excerpt.append(i + 1).append(": ").append(sourceLines[i]);
        }
        return excerpt.toString();
    }

    private static int commonPrefix(String left, String right) {
        int length = Math.min(left.length(), right.length());
        int index = 0;
        while (index < length && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }
}
