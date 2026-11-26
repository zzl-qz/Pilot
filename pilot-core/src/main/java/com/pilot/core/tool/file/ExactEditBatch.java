package com.pilot.core.tool.file;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    record Applied(String content, int count, int oldLines, int newLines, int tolerantMatches,
        List<String> lineRanges) {
        Applied(String content, int count, int oldLines, int newLines) {
            this(content, count, oldLines, newLines, 0, List.of());
        }

        Applied(String content, int count, int oldLines, int newLines, int tolerantMatches) {
            this(content, count, oldLines, newLines, tolerantMatches, List.of());
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
        String eol = dominantEol(body);
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
        List<String> lineRanges = new ArrayList<>(matches.size());
        int cursor = 0;
        int lineNumber = 1;
        for (Match match : matches) {
            String gap = normalized.substring(cursor, match.start());
            updated.append(gap);
            lineNumber += countNewlines(gap);
            int startLine = lineNumber;
            String repl = match.replacement();
            updated.append(repl);
            int addedNewlines = countNewlines(repl);
            int endLine = repl.isEmpty() ? startLine
                : startLine + Math.max(0, repl.endsWith("\n") ? addedNewlines - 1 : addedNewlines);
            lineRanges.add(startLine == endLine ? "L" + startLine : "L" + startLine + "-L" + endLine);
            lineNumber += addedNewlines;
            cursor = match.end();
        }
        updated.append(normalized, cursor, normalized.length());
        String result = bom + updated.toString().replace("\n", eol);
        return new Applied(result, edits.size(), oldLines, newLines, tolerantMatches, List.copyOf(lineRanges));
    }

    static String dominantEol(String body) {
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\r') {
                if (i + 1 < body.length() && body.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        if (crlf > 0 && crlf >= lf && crlf >= cr) {
            return "\r\n";
        }
        if (cr > 0 && cr > lf) {
            return "\r";
        }
        return "\n";
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static Match locateExact(String normalized, String search, String replacement, int editIndex) {
        int start = normalized.indexOf(search);
        if (start < 0) {
            return null;
        }
        int second = normalized.indexOf(search, start + search.length());
        if (second >= 0) {
            List<Integer> lines = new ArrayList<>();
            int pos = start;
            while (pos >= 0 && lines.size() < 8) {
                lines.add(lineNumberAt(normalized, pos));
                pos = normalized.indexOf(search, pos + Math.max(1, search.length()));
            }
            throw new IllegalArgumentException(
                "edits[" + editIndex + "] 的 old_string 匹配到多处（第 " + formatLineNumbers(lines) + " 行），无法唯一定位。请扩大上下文。");
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
        List<Integer> trailingMatches = new ArrayList<>();
        for (int i = 0; i <= spans.size() - windowSize; i++) {
            if (endsWithNewline && !spans.get(i + windowSize - 1).hasNewline()) {
                continue;
            }
            if (matchesTrimmedTrailing(spans, i, needleLines)) {
                trailingMatches.add(i);
                if (trailingMatches.size() >= 8) {
                    break;
                }
            }
        }
        if (trailingMatches.size() > 1) {
            throw new IllegalArgumentException("edits[" + editIndex + "] 的 old_string（忽略行尾空白后）匹配到多处（第 "
                + formatZeroBasedLines(trailingMatches) + " 行），无法唯一定位。请扩大上下文。");
        }
        if (trailingMatches.size() == 1) {
            int trailingMatchStart = trailingMatches.getFirst();
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
        List<Integer> indentMatches = new ArrayList<>();
        String matchedFileIndent = null;
        for (int i = 0; i <= spans.size() - windowSize; i++) {
            if (endsWithNewline && !spans.get(i + windowSize - 1).hasNewline()) {
                continue;
            }
            String fileIndent = matchRelativeIndent(spans, i, dedentedNeedle);
            if (fileIndent != null) {
                indentMatches.add(i);
                if (matchedFileIndent == null) {
                    matchedFileIndent = fileIndent;
                }
                if (indentMatches.size() >= 8) {
                    break;
                }
            }
        }
        if (indentMatches.size() > 1) {
            throw new IllegalArgumentException("edits[" + editIndex + "] 的 old_string（忽略整体缩进差异后）匹配到多处（第 "
                + formatZeroBasedLines(indentMatches) + " 行），无法唯一定位。请扩大上下文。");
        }
        if (indentMatches.size() == 1) {
            int indentMatchStart = indentMatches.getFirst();
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

    /** 多行滑动窗口打分：综合全部非空检索行定位最接近片段，避免仅按首行（如 } / catch / import）误指到文件开头或其他方法。 */
    static String closestExcerpt(String content, String search) {
        String[] sourceLines = content.split("\\R", -1);
        String[] searchLines = search.split("\\R", -1);
        List<String> anchors = new ArrayList<>();
        String longestAnchor = "";
        for (String line : searchLines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                anchors.add(trimmed);
                if (trimmed.length() > longestAnchor.length()) {
                    longestAnchor = trimmed;
                }
                if (anchors.size() >= 12) {
                    break;
                }
            }
        }
        if (anchors.isEmpty()) {
            anchors.add(search.strip());
        }
        int windowSpan = Math.max(4, Math.min(24, searchLines.length + 3));
        String[] strippedSource = new String[sourceLines.length];
        for (int i = 0; i < sourceLines.length; i++) {
            strippedSource[i] = sourceLines[i].strip();
        }
        int bestLine = 0;
        int bestScore = -1;
        for (int i = 0; i < sourceLines.length; i++) {
            int end = Math.min(sourceLines.length, i + windowSpan);
            int score = 0;
            int searchCursor = i;
            for (String anchor : anchors) {
                int lineBest = 0;
                int matchedAt = -1;
                for (int j = searchCursor; j < end; j++) {
                    String candidate = strippedSource[j];
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    int s = lineSimilarity(candidate, anchor);
                    if (s > lineBest) {
                        lineBest = s;
                        matchedAt = j;
                        if (candidate.equals(anchor)) {
                            break;
                        }
                    }
                }
                if (lineBest > 0) {
                    score += lineBest;
                    if (anchor.equals(longestAnchor) && lineBest >= anchor.length()) {
                        score += anchor.length();
                    }
                    if (matchedAt >= 0) {
                        searchCursor = Math.min(end, matchedAt + 1);
                    }
                }
            }
            // 同分时偏爱首行最贴合当前起点的位置
            if (!anchors.isEmpty()) {
                score += lineSimilarity(strippedSource[i], anchors.getFirst()) / 2;
            }
            if (score > bestScore) {
                bestScore = score;
                bestLine = i;
            }
        }
        int from = Math.max(0, bestLine - 2);
        int to = Math.min(sourceLines.length, bestLine + Math.min(24, Math.max(6, searchLines.length + 3)));
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(i + 1).append(": ").append(sourceLines[i]);
        }
        String mismatch = firstMismatchHint(sourceLines, bestLine, searchLines);
        if (!mismatch.isEmpty()) {
            out.append('\n').append(mismatch);
        }
        return out.toString();
    }

    /** 对比最接近片段与传入 old_string，指出首次字符级不一致的精确行列与局部片段，帮助模型一次修正转义或符号笔误。 */
    private static String firstMismatchHint(String[] sourceLines, int bestLine, String[] searchLines) {
        int startOffset = 0;
        while (startOffset < searchLines.length && searchLines[startOffset].isBlank()) {
            startOffset++;
        }
        int limit = Math.min(searchLines.length - startOffset, sourceLines.length - bestLine);
        for (int k = 0; k < limit; k++) {
            String actual = sourceLines[bestLine + k];
            String expected = searchLines[startOffset + k];
            if (!actual.equals(expected)) {
                int col = commonPrefix(actual, expected);
                return "首次不一致：第 " + (bestLine + k + 1) + " 行第 " + (col + 1) + " 列\n  文件：" + snippetAround(actual, col)
                    + "\n  传入：" + snippetAround(expected, col);
            }
        }
        return "";
    }

    private static String snippetAround(String line, int column) {
        int start = Math.max(0, column - 20);
        int end = Math.min(line.length(), column + 40);
        String prefix = start > 0 ? "…" : "";
        String suffix = end < line.length() ? "…" : "";
        return prefix + line.substring(start, end) + suffix;
    }

    private static int lineSimilarity(String candidate, String needle) {
        if (needle.isEmpty() || candidate.isEmpty()) {
            return 0;
        }
        if (candidate.equals(needle)) {
            return 16 + needle.length() * 2;
        }
        int score = commonPrefix(candidate, needle);
        if (candidate.contains(needle) || needle.contains(candidate)) {
            score += Math.min(candidate.length(), needle.length());
        }
        return score;
    }

    static String formatLineNumbers(List<Integer> oneBasedLines) {
        return oneBasedLines.stream().map(String::valueOf).collect(Collectors.joining("、"));
    }

    static String formatZeroBasedLines(List<Integer> zeroBasedLines) {
        return zeroBasedLines.stream().map(i -> String.valueOf(i + 1)).collect(Collectors.joining("、"));
    }

    private static int lineNumberAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
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
