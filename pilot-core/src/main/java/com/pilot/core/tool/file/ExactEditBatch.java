package com.pilot.core.tool.file;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 一次验证并应用同一文件里的多个替换；任何一处失败都不产生部分修改。
 *
 * <p>匹配优先级（每档均要求唯一命中，命中多处一律报错，绝不猜测）：
 * <ol>
 *   <li>精确逐字符匹配；</li>
 *   <li>整行忽略行尾空白匹配；</li>
 *   <li>整行保持相对缩进一致的整体缩进偏移匹配（自动按目标缩进对齐 {@code new_string}）。</li>
 * </ol>
 */
final class ExactEditBatch {
    record Edit(String oldText, String newText) {
    }

    record Applied(String content, int count, int oldLines, int newLines, int tolerantMatches) {
        Applied(String content, int count, int oldLines, int newLines) {
            this(content, count, oldLines, newLines, 0);
        }
    }

    private record Match(int start, int end, String replacement, boolean tolerant) {
    }

    private record LineSpan(int start, int end, String text, boolean hasNewline) {
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
        List<LineSpan> spans = null;
        List<Match> matches = new ArrayList<>(edits.size());
        int oldLines = 0;
        int newLines = 0;
        int tolerantMatches = 0;
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            String search = normalize(edit.oldText());
            if (search.isEmpty()) {
                throw new IllegalArgumentException("edits[" + i + "] 的 old_string 不能为空");
            }
            String replacement = normalize(edit.newText());
            Match located = locateExact(normalized, search, replacement, i);
            if (located == null) {
                if (spans == null) {
                    spans = splitLineSpans(normalized);
                }
                located = locateTolerant(spans, search, replacement, i);
            }
            if (located == null) {
                throw new IllegalArgumentException("edits[" + i + "] 未找到 old_string。最接近的文件片段：\n"
                    + closestExcerpt(normalized, search) + "\n请基于该片段提供更精确的旧内容。");
            }
            if (located.tolerant()) {
                tolerantMatches++;
            }
            matches.add(located);
            oldLines += (int)search.lines().count();
            newLines += (int)located.replacement().lines().count();
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
        return new Applied(result, edits.size(), oldLines, newLines, tolerantMatches);
    }

    private static Match locateExact(String normalized, String search, String replacement, int editIndex) {
        int start = normalized.indexOf(search);
        if (start < 0) {
            return null;
        }
        if (normalized.indexOf(search, start + search.length()) >= 0) {
            throw new IllegalArgumentException("edits[" + editIndex + "] 的 old_string 匹配到多处，无法唯一定位。请扩大上下文。");
        }
        return new Match(start, start + search.length(), replacement, false);
    }

    private static Match locateTolerant(List<LineSpan> spans, String search, String replacement, int editIndex) {
        boolean endsWithNewline = search.endsWith("\n");
        String core = endsWithNewline ? search.substring(0, search.length() - 1) : search;
        if (core.isEmpty()) {
            return null;
        }
        String[] needleLines = core.split("\n", -1);
        int windowSize = needleLines.length;
        if (windowSize > spans.size()) {
            return null;
        }

        // 档位 2：逐行忽略行尾空白
        int trailingMatchStart = -1;
        for (int i = 0; i <= spans.size() - windowSize; i++) {
            if (endsWithNewline && !spans.get(i + windowSize - 1).hasNewline()) {
                continue;
            }
            if (matchesTrimmedTrailing(spans, i, needleLines)) {
                if (trailingMatchStart >= 0) {
                    throw new IllegalArgumentException(
                        "edits[" + editIndex + "] 的 old_string（忽略行尾空白后）匹配到多处，无法唯一定位。请扩大上下文。");
                }
                trailingMatchStart = i;
            }
        }
        if (trailingMatchStart >= 0) {
            int startOffset = spans.get(trailingMatchStart).start();
            LineSpan last = spans.get(trailingMatchStart + windowSize - 1);
            int endOffset = last.end() + (endsWithNewline ? 1 : 0);
            return new Match(startOffset, endOffset, replacement, true);
        }

        // 档位 3：整体缩进偏移（至少包含一行非空内容，相对缩进必须完全一致）
        String searchBaseIndent = commonMinIndent(needleLines);
        if (searchBaseIndent == null) {
            return null;
        }
        String[] dedentedNeedle = dedentLines(needleLines, searchBaseIndent);
        if (dedentedNeedle == null) {
            return null;
        }
        int indentMatchStart = -1;
        String matchedFileIndent = null;
        for (int i = 0; i <= spans.size() - windowSize; i++) {
            if (endsWithNewline && !spans.get(i + windowSize - 1).hasNewline()) {
                continue;
            }
            String fileIndent = matchRelativeIndent(spans, i, dedentedNeedle);
            if (fileIndent != null) {
                if (indentMatchStart >= 0) {
                    throw new IllegalArgumentException(
                        "edits[" + editIndex + "] 的 old_string（忽略整体缩进差异后）匹配到多处，无法唯一定位。请扩大上下文。");
                }
                indentMatchStart = i;
                matchedFileIndent = fileIndent;
            }
        }
        if (indentMatchStart >= 0) {
            int startOffset = spans.get(indentMatchStart).start();
            LineSpan last = spans.get(indentMatchStart + windowSize - 1);
            int endOffset = last.end() + (endsWithNewline ? 1 : 0);
            String aligned = reindentReplacement(replacement, searchBaseIndent, matchedFileIndent);
            return new Match(startOffset, endOffset, aligned, true);
        }
        return null;
    }

    private static boolean matchesTrimmedTrailing(List<LineSpan> spans, int start, String[] needleLines) {
        for (int j = 0; j < needleLines.length; j++) {
            if (!spans.get(start + j).text().stripTrailing().equals(needleLines[j].stripTrailing())) {
                return false;
            }
        }
        return true;
    }

    private static String matchRelativeIndent(List<LineSpan> spans, int start, String[] dedentedNeedle) {
        String[] window = new String[dedentedNeedle.length];
        for (int j = 0; j < dedentedNeedle.length; j++) {
            String line = spans.get(start + j).text();
            if (line.isBlank() != dedentedNeedle[j].isEmpty()) {
                return null;
            }
            window[j] = line;
        }
        String fileBaseIndent = commonMinIndent(window);
        if (fileBaseIndent == null) {
            return null;
        }
        String[] dedentedWindow = dedentLines(window, fileBaseIndent);
        if (dedentedWindow == null) {
            return null;
        }
        for (int j = 0; j < dedentedNeedle.length; j++) {
            if (!dedentedWindow[j].equals(dedentedNeedle[j])) {
                return null;
            }
        }
        return fileBaseIndent;
    }

    /** 计算多行中所有非空行的最短公共前导空白；全为空白行或缩进混用不兼容时返回 null。 */
    private static String commonMinIndent(String[] lines) {
        String min = null;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String indent = leadingWhitespace(line);
            if (min == null || indent.length() < min.length()) {
                min = indent;
            }
        }
        if (min == null) {
            return null;
        }
        for (String line : lines) {
            if (!line.isBlank() && !line.startsWith(min)) {
                return null;
            }
        }
        return min;
    }

    private static String[] dedentLines(String[] lines, String baseIndent) {
        String[] result = new String[lines.length];
        int stripLen = baseIndent.length();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                result[i] = "";
            } else {
                if (!lines[i].startsWith(baseIndent)) {
                    return null;
                }
                result[i] = lines[i].substring(stripLen).stripTrailing();
            }
        }
        return result;
    }

    private static String reindentReplacement(String replacement, String searchBaseIndent, String fileBaseIndent) {
        if (replacement.isEmpty() || searchBaseIndent.equals(fileBaseIndent)) {
            return replacement;
        }
        boolean trailingNewline = replacement.endsWith("\n");
        String core = trailingNewline ? replacement.substring(0, replacement.length() - 1) : replacement;
        String[] lines = core.split("\n", -1);
        StringBuilder out = new StringBuilder(replacement.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if (line.isBlank()) {
                out.append(line);
            } else if (!searchBaseIndent.isEmpty() && line.startsWith(searchBaseIndent)) {
                out.append(fileBaseIndent).append(line.substring(searchBaseIndent.length()));
            } else if (searchBaseIndent.isEmpty()) {
                out.append(fileBaseIndent).append(line);
            } else {
                out.append(line);
            }
        }
        if (trailingNewline) {
            out.append('\n');
        }
        return out.toString();
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static List<LineSpan> splitLineSpans(String text) {
        List<LineSpan> spans = new ArrayList<>();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                spans.add(new LineSpan(start, len, text.substring(start, len), false));
                break;
            }
            spans.add(new LineSpan(start, nl, text.substring(start, nl), true));
            start = nl + 1;
        }
        if (len == 0) {
            spans.add(new LineSpan(0, 0, "", false));
        }
        return spans;
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
        int to = Math.min(sourceLines.length, bestLine + Math.max(4, searchLines.length + 2));
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(i + 1).append(": ").append(sourceLines[i]);
        }
        return out.toString();
    }

    private static int commonPrefix(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int i = 0;
        while (i < limit && left.charAt(i) == right.charAt(i)) {
            i++;
        }
        return i;
    }
}
