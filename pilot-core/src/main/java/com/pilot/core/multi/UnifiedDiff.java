package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小 unified diff 生成器：子 Agent 只提 diff，不落地真实文件。
 *
 * <p>输出与 {@code git apply} / {@code patch -p1} 兼容（带 {@code diff --git} 头与 3 行上下文），
 * 这样人工审阅、合并预检与后续落盘可以共用同一份变更文本。
 */
public final class UnifiedDiff {
    /** 上下文行数，与 git 默认一致。 */
    private static final int CONTEXT = 3;
    /** LCS 动态规划的最大格子数；超过就退化成「整段替换」，避免大文件把内存打满。 */
    private static final long MAX_CELLS = 4_000_000L;
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private UnifiedDiff() {
    }

    /**
     * 生成一份完整的 unified diff。
     *
     * @param path    工作区相对路径（展示用）
     * @param oldText 改动前内容；{@code null} 表示新增文件
     * @param newText 改动后内容
     * @return diff 文本；内容没有变化时返回空字符串
     */
    public static String create(String path, String oldText, String newText) {
        String target = normalize(path);
        String after = newText == null ? "" : newText;
        if (oldText == null) {
            return newFile(target, after);
        }
        if (oldText.equals(after)) {
            return "";
        }
        List<Op> ops = script(split(oldText), split(after));
        String body = hunks(ops);
        if (body.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("diff --git a/").append(target).append(" b/").append(target).append('\n');
        out.append("--- a/").append(target).append('\n');
        out.append("+++ b/").append(target).append('\n');
        out.append(body);
        return out.toString();
    }

    /** 改动是否为空（新增文件也算改动）。 */
    public static boolean isEmpty(String diff) {
        return diff == null || diff.isBlank();
    }

    /**
     * 从 diff 里取出「被改到的旧文件行区间」（从 1 开始，闭区间）。
     *
     * <p>合并预检用它判断两个改动是否重叠：纯插入返回 {@code [point, point]}。
     * 解析失败时返回一个覆盖全文件的区间，宁可多报冲突也不要漏报。
     */
    public static List<int[]> oldRanges(String diff) {
        List<int[]> ranges = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return ranges;
        }
        int cursor = 0;
        int first = -1;
        int last = -1;
        boolean inHunk = false;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("@@")) {
                flush(ranges, first, last);
                first = -1;
                last = -1;
                Matcher matcher = HUNK_HEADER.matcher(line);
                cursor = matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
                inHunk = true;
                continue;
            }
            if (!inHunk || line.isEmpty()) {
                continue;
            }
            char mark = line.charAt(0);
            if (mark == ' ') {
                cursor++;
            } else if (mark == '-') {
                if (first < 0) {
                    first = cursor;
                }
                last = cursor;
                cursor++;
            } else if (mark == '+') {
                int point = Math.max(1, cursor - (first < 0 ? 1 : 0));
                if (first < 0) {
                    first = point;
                }
                last = Math.max(last, first);
            }
        }
        flush(ranges, first, last);
        if (ranges.isEmpty()) {
            ranges.add(new int[]{1, Integer.MAX_VALUE});
        }
        return ranges;
    }

    /** 统计新增 / 删除行数，界面与报告用。 */
    public static int[] countChanges(String diff) {
        int added = 0;
        int removed = 0;
        if (diff == null || diff.isBlank()) {
            return new int[]{0, 0};
        }
        boolean inHunk = false;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                inHunk = false;
                continue;
            }
            if (line.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (!inHunk || line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '+') {
                added++;
            } else if (line.charAt(0) == '-') {
                removed++;
            }
        }
        return new int[]{added, removed};
    }

    private static void flush(List<int[]> ranges, int first, int last) {
        if (first > 0 && last >= first) {
            ranges.add(new int[]{first, last});
        }
    }

    private static String newFile(String path, String text) {
        List<String> lines = split(text);
        StringBuilder out = new StringBuilder();
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        out.append("new file mode 100644\n");
        out.append("--- /dev/null\n");
        out.append("+++ b/").append(path).append('\n');
        out.append("@@ -0,0 +1,").append(lines.size()).append(" @@\n");
        for (String line : lines) {
            out.append('+').append(line).append('\n');
        }
        return out.toString();
    }

    /** 逐个 hunk 拼接；相邻改动间隔小于两倍上下文时并进同一个 hunk。 */
    private static String hunks(List<Op> ops) {
        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).type() != ' ') {
                changes.add(i);
            }
        }
        if (changes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < changes.size()) {
            int from = Math.max(0, changes.get(index) - CONTEXT);
            int lastChange = changes.get(index);
            int cursor = index;
            while (cursor + 1 < changes.size() && changes.get(cursor + 1) - lastChange <= CONTEXT * 2) {
                cursor++;
                lastChange = changes.get(cursor);
            }
            int to = Math.min(ops.size(), lastChange + CONTEXT + 1);
            out.append(hunk(ops, from, to));
            index = cursor + 1;
        }
        return out.toString();
    }

    private static String hunk(List<Op> ops, int from, int to) {
        int oldStart = 1;
        int newStart = 1;
        for (int i = 0; i < from; i++) {
            char type = ops.get(i).type();
            if (type != '+') {
                oldStart++;
            }
            if (type != '-') {
                newStart++;
            }
        }
        int oldCount = 0;
        int newCount = 0;
        StringBuilder body = new StringBuilder();
        for (int i = from; i < to; i++) {
            Op op = ops.get(i);
            body.append(op.type() == ' ' ? ' ' : op.type()).append(op.line()).append('\n');
            if (op.type() != '+') {
                oldCount++;
            }
            if (op.type() != '-') {
                newCount++;
            }
        }
        return "@@ -" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " @@\n" + body;
    }

    /**
     * 行级编辑脚本：公共前后缀先剥掉，中间用 LCS 求最短编辑序列。
     * 中间段过大时退化成「整段删除 + 整段新增」，代价是 diff 更粗，但不会拖垮内存。
     */
    static List<Op> script(List<String> before, List<String> after) {
        List<Op> ops = new ArrayList<>();
        int prefix = 0;
        int maxPrefix = Math.min(before.size(), after.size());
        while (prefix < maxPrefix && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        int maxSuffix = Math.min(before.size(), after.size()) - prefix;
        while (suffix < maxSuffix
                && before.get(before.size() - 1 - suffix).equals(after.get(after.size() - 1 - suffix))) {
            suffix++;
        }
        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(' ', before.get(i)));
        }
        List<String> left = before.subList(prefix, before.size() - suffix);
        List<String> right = after.subList(prefix, after.size() - suffix);
        if ((long) left.size() * right.size() > MAX_CELLS) {
            left.forEach(line -> ops.add(new Op('-', line)));
            right.forEach(line -> ops.add(new Op('+', line)));
        } else {
            ops.addAll(lcsOps(left, right));
        }
        for (int i = before.size() - suffix; i < before.size(); i++) {
            ops.add(new Op(' ', before.get(i)));
        }
        return ops;
    }

    private static List<Op> lcsOps(List<String> left, List<String> right) {
        int n = left.size();
        int m = right.size();
        int[][] table = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                table[i][j] = left.get(i).equals(right.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (left.get(i).equals(right.get(j))) {
                ops.add(new Op(' ', left.get(i)));
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                ops.add(new Op('-', left.get(i)));
                i++;
            } else {
                ops.add(new Op('+', right.get(j)));
                j++;
            }
        }
        while (i < n) {
            ops.add(new Op('-', left.get(i++)));
        }
        while (j < m) {
            ops.add(new Op('+', right.get(j++)));
        }
        return ops;
    }

    /** 按行切分：去掉 CRLF 干扰，末尾换行不产生一个虚假的空行。 */
    private static List<String> split(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String normalize(String path) {
        String value = path == null ? "" : path.replace('\\', '/').strip();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    /** @param type {@code ' '} 上下文 / {@code '-'} 删除 / {@code '+'} 新增 */
    private record Op(char type, String line) {
    }
}
