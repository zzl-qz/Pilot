package com.pilot.core.lint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只报告这次新出现的复制。
 * 连续 6 行出现一次就报。连续 4 行要凑满 3 处才报。
 * 单行只在同一条非平凡语句新增满 4 次时报。return 1 这类不报。
 */
final class ReuseScan {
    static final int WINDOW = 6;
    private static final int SHORT_WINDOW = 4;
    private static final int SHORT_TIMES = 3;
    private static final int LINE_TIMES = 4;
    private static final Pattern STRING =
        Pattern.compile("\"(?:\\\\[\\s\\S]|[^\"\\\\])*+\"|'(?:\\\\[\\s\\S]|[^'\\\\])*+'");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])");
    private static final Pattern IDENT = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
    /** 纯方法/构造器声明行与简单字段赋值不算业务控制逻辑，避免连续 fluent setter 误报换名复制。 */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
        "^(?:public|private|protected|static|final|synchronized|default|abstract|\\s)+[\\w<>\\[\\],.?\\s]+\\([^)]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{?$");
    private static final Pattern SIMPLE_FIELD_ASSIGN =
        Pattern.compile("^this\\.[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*[^;]+;$");
    private static final Set<String> KEEP = Set.of("if", "else", "for", "while", "return", "new", "null", "true",
        "false", "try", "catch", "throw", "switch", "case", "break", "continue", "function", "public", "private",
        "protected", "static", "class", "void", "const", "let", "var", "echo");

    private ReuseScan() {
    }

    static List<String> scan(String path, String before, String after, List<String> siblings) {
        List<String> beforeLines = meaningful(before);
        List<String> afterLines = meaningful(after);
        boolean[] added = addedMask(beforeLines, afterLines);
        List<String> out = new ArrayList<>();
        String exact = copied(beforeLines, afterLines, added, siblings, WINDOW, false, 1);
        String renamed = exact == null ? copied(beforeLines, afterLines, added, siblings, WINDOW, true, 1) : null;
        String shortExact = exact == null && renamed == null
            ? copied(beforeLines, afterLines, added, siblings, SHORT_WINDOW, false, SHORT_TIMES) : null;
        String shortRenamed = exact == null && renamed == null && shortExact == null
            ? copied(beforeLines, afterLines, added, siblings, SHORT_WINDOW, true, SHORT_TIMES) : null;
        if (exact != null) {
            out.add(path + ": 有至少 " + WINDOW + " 行连续代码和已有实现逐字相同，属于原样复制（命中片段：" + exact + "）");
        } else if (renamed != null) {
            out.add(path + ": 有至少 " + WINDOW + " 行逻辑和已有实现只是名字不同，属于换名复制（命中片段：" + renamed + "）");
        } else if (shortExact != null) {
            out.add(path + ": 有 " + SHORT_WINDOW + " 行连续代码重复了至少 " + SHORT_TIMES + " 次，属于短重复（命中片段：" + shortExact + "）");
        } else if (shortRenamed != null) {
            out.add(
                path + ": 有 " + SHORT_WINDOW + " 行逻辑只是名字不同，而且重复了至少 " + SHORT_TIMES + " 次（命中片段：" + shortRenamed + "）");
        }
        String line = repeatedLine(beforeLines, afterLines);
        if (line != null) {
            out.add(path + ": 语句 " + line + " 这次新增了至少 " + LINE_TIMES + " 次。先看能不能收成一处");
        }
        return List.copyOf(out);
    }

    private static String copied(List<String> beforeLines, List<String> afterLines, boolean[] added,
        List<String> siblings, int window, boolean structural, int times) {
        Map<String, Integer> before = windows(beforeLines, structural, window);
        Map<String, String> samples = new HashMap<>();
        Map<String, Integer> after = countsOf(afterLines, null, structural, window, null);
        Map<String, Integer> fresh = countsOf(afterLines, added, structural, window, samples);
        Map<String, Integer> corpus = new HashMap<>(before);
        if (siblings != null) {
            for (String sibling : siblings) {
                add(corpus, windows(meaningful(sibling), structural, window));
            }
        }
        for (Map.Entry<String, Integer> entry : fresh.entrySet()) {
            String key = entry.getKey();
            int afterCount = after.getOrDefault(key, 0);
            int oldCount = before.getOrDefault(key, 0);
            int seen = corpus.getOrDefault(key, 0);
            if (afterCount <= oldCount) {
                continue;
            }
            if (times <= 1 && (seen > 0 || afterCount >= 2)) {
                return samples.getOrDefault(key, "");
            }
            int total = seen - oldCount + afterCount;
            if (times > 1 && total >= times) {
                return samples.getOrDefault(key, "");
            }
        }
        return null;
    }

    private static String repeatedLine(List<String> beforeLines, List<String> afterLines) {
        Map<String, Integer> existing = new HashMap<>();
        for (String line : beforeLines) {
            existing.merge(line, 1, Integer::sum);
        }
        Map<String, Integer> added = new HashMap<>();
        for (String line : afterLines) {
            int left = existing.getOrDefault(line, 0);
            if (left > 0) {
                existing.put(line, left - 1);
                continue;
            }
            if (!trivial(line)) {
                added.merge(line, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : added.entrySet()) {
            if (entry.getValue() >= LINE_TIMES) {
                String text = entry.getKey();
                return text.length() > 80 ? text.substring(0, 80) : text;
            }
        }
        return null;
    }

    static boolean trivial(String line) {
        String text = line.strip();
        return text.equals("{") || text.equals("}") || text.equals(");") || text.equals(";") || text.equals("try {")
            || text.equals("else {") || text.equals("} else {") || text.equals("finally {")
            || text.equals("} finally {") || text.equals("break;") || text.equals("continue;")
            || text.matches("return(?:\\s+(?:0|1|-1|null|true|false|this|\"\"|''))?;?");
    }

    private static Map<String, Integer> windows(List<String> lines, boolean structural, int window) {
        return countsOf(lines, null, structural, window, null);
    }

    private static void add(Map<String, Integer> into, Map<String, Integer> more) {
        for (Map.Entry<String, Integer> entry : more.entrySet()) {
            into.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static Map<String, Integer> countsOf(List<String> lines, boolean[] added, boolean structural, int window,
        Map<String, String> samples) {
        Map<String, Integer> counts = new HashMap<>();
        if (lines.size() < window) {
            return counts;
        }
        Map<String, Integer> lastEnd = new HashMap<>();
        int minLogic = window >= WINDOW ? 3 : 2;
        int minKey = window >= WINDOW ? 40 : 24;
        int minAdded = window >= WINDOW ? 3 : 2;
        for (int i = 0; i + window <= lines.size(); i++) {
            if (allTrivial(lines, i, window)) {
                continue;
            }
            if (added != null && addedInWindow(added, i, window) < minAdded) {
                continue;
            }
            if (structural && logic(lines, i, window) < minLogic) {
                continue;
            }
            String key = key(lines, i, structural, window);
            if (key.length() < minKey) {
                continue;
            }
            // 同文件内仅统计互不重叠的窗口，避免连续早返回守卫链因滑动窗口重叠自匹配误报换名复制
            if (i < lastEnd.getOrDefault(key, 0)) {
                continue;
            }
            lastEnd.put(key, i + window);
            counts.merge(key, 1, Integer::sum);
            if (samples != null) {
                samples.putIfAbsent(key, preview(lines, i, window));
            }
        }
        return counts;
    }

    private static int addedInWindow(boolean[] added, int start, int window) {
        int count = 0;
        for (int i = start; i < start + window; i++) {
            if (added[i]) {
                count++;
            }
        }
        return count;
    }

    /** 标记 afterLines 中相对 beforeLines 真正新增或改写的行，避免向既有相似方法插入 1-2 行守卫时误报换名复制。 */
    private static boolean[] addedMask(List<String> before, List<String> after) {
        boolean[] mask = new boolean[after.size()];
        if (before.isEmpty()) {
            Arrays.fill(mask, true);
            return mask;
        }
        int prefix = 0;
        int limit = Math.min(before.size(), after.size());
        while (prefix < limit && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int bEnd = before.size();
        int aEnd = after.size();
        while (bEnd > prefix && aEnd > prefix && before.get(bEnd - 1).equals(after.get(aEnd - 1))) {
            bEnd--;
            aEnd--;
        }
        int m = bEnd - prefix;
        int n = aEnd - prefix;
        if (n <= 0) {
            return mask;
        }
        if (m <= 0) {
            Arrays.fill(mask, prefix, aEnd, true);
            return mask;
        }
        if ((long)m * n > 2_000_000L) {
            markExcess(before, after, prefix, bEnd, aEnd, mask);
            return mask;
        }
        markLcsDiff(before, after, prefix, m, n, mask);
        return mask;
    }

    private static void markLcsDiff(List<String> before, List<String> after, int offset, int m, int n, boolean[] mask) {
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            String left = before.get(offset + i);
            for (int j = n - 1; j >= 0; j--) {
                dp[i][j] =
                    left.equals(after.get(offset + j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (before.get(offset + i).equals(after.get(offset + j))) {
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                mask[offset + j] = true;
                j++;
            }
        }
        while (j < n) {
            mask[offset + j] = true;
            j++;
        }
    }

    private static void markExcess(List<String> before, List<String> after, int offset, int bEnd, int aEnd,
        boolean[] mask) {
        Map<String, Integer> remaining = new HashMap<>();
        for (int i = offset; i < bEnd; i++) {
            remaining.merge(before.get(i), 1, Integer::sum);
        }
        for (int j = offset; j < aEnd; j++) {
            String line = after.get(j);
            int left = remaining.getOrDefault(line, 0);
            if (left > 0) {
                remaining.put(line, left - 1);
            } else {
                mask[j] = true;
            }
        }
    }

    private static String preview(List<String> lines, int start, int window) {
        for (int i = start; i < start + window; i++) {
            String text = lines.get(i).strip();
            if (!trivial(text)) {
                return text.length() > 80 ? text.substring(0, 80) + "…" : text;
            }
        }
        String first = lines.get(start).strip();
        return first.length() > 80 ? first.substring(0, 80) + "…" : first;
    }

    private static boolean allTrivial(List<String> lines, int start, int window) {
        for (int i = start; i < start + window; i++) {
            if (!trivial(lines.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int logic(List<String> lines, int start, int window) {
        int n = 0;
        for (int i = start; i < start + window; i++) {
            String line = lines.get(i).strip();
            if (trivial(line) || METHOD_DECLARATION.matcher(line).matches()
                || SIMPLE_FIELD_ASSIGN.matcher(line).matches()) {
                continue;
            }
            if (line.contains("(") || line.contains(" if ") || line.startsWith("if ") || line.contains(" for ")
                || line.startsWith("for ") || line.contains("return ") || line.contains("while ")
                || line.startsWith("while ")) {
                n++;
            }
        }
        return n;
    }

    private static String key(List<String> lines, int start, boolean structural, int window) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < start + window; i++) {
            String line = lines.get(i);
            out.append(structural ? skeleton(line) : line.strip()).append('\n');
        }
        return out.toString();
    }

    static String skeleton(String line) {
        String masked = STRING.matcher(line).replaceAll("\"\"");
        masked = NUMBER.matcher(masked).replaceAll("0");
        Matcher id = IDENT.matcher(masked);
        StringBuilder out = new StringBuilder();
        while (id.find()) {
            String token = id.group();
            id.appendReplacement(out, KEEP.contains(token) ? token : "v");
        }
        id.appendTail(out);
        return out.toString().replaceAll("\\s+", " ").strip();
    }

    private static List<String> meaningful(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#") || line.startsWith("*")
                || line.startsWith("/*") || line.startsWith("import ") || line.startsWith("package ")
                || line.startsWith("namespace ") || line.startsWith("use ") || line.startsWith("@")) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }
}
