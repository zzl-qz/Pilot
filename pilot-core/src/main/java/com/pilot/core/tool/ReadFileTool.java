package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 按行读取工作区文本。结果里只有请求的窗口，行号用文件里的绝对行号。 */
public final class ReadFileTool implements Tool {
    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_CHARS = 2000;
    private static final int BINARY_SAMPLE_BYTES = 8000;
    private static final int TAB_WIDTH = 4;

    private final PathValidator paths;

    public ReadFileTool(PathValidator paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取工作区内文本文件的指定行范围，返回带行号的内容。路径必须是工作区内的相对路径。"
                + "start_line 默认 1；正数从开头数，负数从末尾数，-1 是最后一行。"
                + "line_count 默认 2000，最多 2000。"
                + "要看某一行所在的代码块就传 anchor_line（同样支持负数）；"
                + "block_levels 默认 1，表示向外扩几层，0 表示扩到最外层。"
                + "大文件按行读取，结果写明行范围和总行数；后面还有内容时会给出下一行行号。单行过长会截断并注明。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "工作区内的相对文件路径"),
                        "start_line", Map.of("type", "integer",
                                "description", "起始行号。正数从 1 开始，负数从末尾数，-1 是最后一行。默认 1"),
                        "line_count", Map.of("type", "integer",
                                "description", "最多读取行数，默认 2000，最大 2000"),
                        "anchor_line", Map.of("type", "integer",
                                "description", "改读这一行所在的代码块。正数从 1 开始，负数从末尾数"),
                        "block_levels", Map.of("type", "integer",
                                "description", "代码块向外扩的层数，默认 1，0 表示扩到最外层")),
                "required", List.of("path"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        if (rawPath == null) {
            return "ERROR: 缺少参数 path";
        }
        IntArg startArg = intArg(args, "start_line");
        IntArg countArg = intArg(args, "line_count");
        IntArg anchorArg = intArg(args, "anchor_line");
        IntArg levelsArg = intArg(args, "block_levels");
        if (startArg.present() && (startArg.value() == null || startArg.value() == 0)) {
            return "ERROR: 参数 start_line 必须是非 0 整数，正数从 1 开始，负数从末尾数";
        }
        if (countArg.present() && (countArg.value() == null || countArg.value() < 1)) {
            return "ERROR: 参数 line_count 必须是大于等于 1 的整数";
        }
        if (anchorArg.present() && (anchorArg.value() == null || anchorArg.value() == 0)) {
            return "ERROR: 参数 anchor_line 必须是非 0 整数，正数从 1 开始，负数从末尾数";
        }
        if (levelsArg.present() && (levelsArg.value() == null || levelsArg.value() < 0)) {
            return "ERROR: 参数 block_levels 必须是大于等于 0 的整数";
        }
        int startLine = startArg.present() ? startArg.value() : 1;
        int lineCount = Math.min(countArg.present() ? countArg.value() : MAX_LINES, MAX_LINES);
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
            if (looksBinary(file)) {
                return "ERROR: 二进制文件，无法读取: " + rawPath;
            }
            if (anchorArg.present()) {
                int levels = levelsArg.present() ? levelsArg.value() : 1;
                return readBlock(file, anchorArg.value(), levels);
            }
            return readSlice(file, startLine, lineCount);
        } catch (IOException e) {
            return "ERROR: 读取失败: " + e.getMessage();
        }
    }

    private static boolean looksBinary(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return ToolSupport.isBinary(in.readNBytes(BINARY_SAMPLE_BYTES));
        }
    }

    private static String readSlice(Path file, int startLine, int lineCount) throws IOException {
        if (startLine < 0) {
            int total = countLines(file);
            if (total == 0) {
                return "(空文件，共 0 行)";
            }
            long resolved = (long) total + startLine + 1;
            boolean clamped = resolved < 1;
            int start = clamped ? 1 : (int) resolved;
            List<String> lines = collect(file, start, lineCount);
            String clampNote = clamped ? "（负向起点超出文件开头，已从第 1 行读起）" : "";
            return render(start, lines, total, "", clampNote);
        }
        Slice slice = scanSlice(file, startLine, lineCount);
        if (slice.total == 0) {
            return "(空文件，共 0 行)";
        }
        if (slice.lines.isEmpty()) {
            return "请求范围从第 " + startLine + " 行开始，但文件共 " + slice.total + " 行（无内容返回）";
        }
        return render(startLine, slice.lines, slice.total, "", "");
    }

    private static String readBlock(Path file, int anchorLine, int levels) throws IOException {
        Outline outline = Outline.scan(file);
        if (outline.total == 0) {
            return "(空文件，共 0 行)";
        }
        long resolved = anchorLine > 0 ? anchorLine : (long) outline.total + anchorLine + 1;
        boolean clamped = resolved < 1;
        int anchor = clamped ? 1 : (int) resolved;
        if (anchor > outline.total) {
            return "请求的代码块锚点在第 " + anchor + " 行，但文件共 " + outline.total + " 行（无内容返回）";
        }
        int[] range = outline.bounds(anchor, levels);
        int from = range[0];
        int to = range[1];
        boolean partial = false;
        if (to - from + 1 > MAX_LINES) {
            partial = true;
            int windowFrom = Math.max(from, anchor - MAX_LINES + 1);
            int windowTo = Math.min(to, windowFrom + MAX_LINES - 1);
            from = Math.max(from, windowTo - MAX_LINES + 1);
            to = windowTo;
        }
        List<String> lines = collect(file, from, to - from + 1);
        String note = "，含第 " + anchor + " 行所在代码块" + (partial ? "（未完整显示）" : "");
        String clampNote = clamped ? "（负向起点超出文件开头，已从第 1 行读起）" : "";
        return render(from, lines, outline.total, note, clampNote);
    }

    private static Slice scanSlice(Path file, int startLine, int lineCount) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Scan scan = new Scan(reader);
            List<String> window = new ArrayList<>();
            int seen = 0;
            while (true) {
                LineChunk chunk = scan.readLine();
                if (chunk == null) {
                    break;
                }
                seen++;
                if (seen >= startLine && window.size() < lineCount) {
                    window.add(chunk.display());
                }
            }
            return new Slice(window, seen);
        }
    }

    private static int countLines(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Scan scan = new Scan(reader);
            int seen = 0;
            while (scan.readLine() != null) {
                seen++;
            }
            return seen;
        }
    }

    private static List<String> collect(Path file, int startLine, int lineCount) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Scan scan = new Scan(reader);
            List<String> window = new ArrayList<>();
            int seen = 0;
            while (window.size() < lineCount) {
                LineChunk chunk = scan.readLine();
                if (chunk == null) {
                    break;
                }
                seen++;
                if (seen >= startLine) {
                    window.add(chunk.display());
                }
            }
            return window;
        }
    }

    private static String render(int startLine, List<String> lines, int total, String blockNote, String afterTotal) {
        int endLine = startLine + lines.size() - 1;
        int width = String.valueOf(endLine).length();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(String.format(Locale.ROOT, "%" + width + "d | %s", startLine + i, lines.get(i)));
        }
        out.append("\n… 显示第 ").append(startLine).append(" 到第 ").append(endLine).append(" 行");
        out.append(blockNote);
        out.append("，文件共 ").append(total).append(" 行");
        out.append(afterTotal);
        if (endLine < total) {
            out.append("，后续内容未显示，从第 ").append(endLine + 1).append(" 行继续");
        }
        out.append(" …");
        return out.toString();
    }

    private static IntArg intArg(Map<String, Object> args, String name) {
        if (args == null || !args.containsKey(name) || args.get(name) == null) {
            return new IntArg(false, null);
        }
        return new IntArg(true, parseInt(args.get(name)));
    }

    private static Integer parseInt(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (numeric == Math.rint(numeric) && numeric >= Integer.MIN_VALUE && numeric <= Integer.MAX_VALUE) {
                return (int) numeric;
            }
            return null;
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text.strip());
                if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                    return (int) parsed;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private record IntArg(boolean present, Integer value) {
    }

    private record Slice(List<String> lines, int total) {
    }

    private record LineChunk(String text, boolean truncated) {
        private String display() {
            return truncated ? text + "…（此行已截断）" : text;
        }
    }

    /** 一次只留一行的前 2000 字，其余字符直接丢掉，用来把总行数数完。 */
    private static final class Scan {
        private final BufferedReader reader;

        private Scan(BufferedReader reader) {
            this.reader = reader;
        }

        private LineChunk readLine() throws IOException {
            StringBuilder text = new StringBuilder();
            boolean any = false;
            boolean truncated = false;
            while (true) {
                int ch = reader.read();
                if (ch < 0) {
                    return any ? new LineChunk(text.toString(), truncated) : null;
                }
                any = true;
                if (ch == '\n') {
                    return new LineChunk(text.toString(), truncated);
                }
                if (ch == '\r') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next >= 0) {
                        reader.reset();
                    }
                    return new LineChunk(text.toString(), truncated);
                }
                if (text.length() < MAX_LINE_CHARS) {
                    text.append((char) ch);
                } else {
                    truncated = true;
                }
            }
        }
    }

    /**
     * 代码块边界。空白行沿用上一行缩进。
     * 向外找更小的缩进当作父级，块内包含更深的行，以及同一层的 else / 收尾括号。
     */
    private static final class Outline {
        private final int[] indent;
        private final boolean[] blank;
        private final boolean[] comment;
        private final boolean[] clause;
        private final int total;

        private Outline(int[] indent, boolean[] blank, boolean[] comment, boolean[] clause) {
            this.indent = indent;
            this.blank = blank;
            this.comment = comment;
            this.clause = clause;
            this.total = indent.length;
        }

        private static Outline scan(Path file) throws IOException {
            List<Integer> indents = new ArrayList<>();
            List<Boolean> blanks = new ArrayList<>();
            List<Boolean> comments = new ArrayList<>();
            List<Boolean> clauses = new ArrayList<>();
            int previous = 0;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Scan scan = new Scan(reader);
                while (true) {
                    LineChunk chunk = scan.readLine();
                    if (chunk == null) {
                        break;
                    }
                    String raw = chunk.text();
                    boolean isBlank = raw.isBlank();
                    int rawIndent = measure(raw);
                    if (!isBlank) {
                        previous = rawIndent;
                    }
                    indents.add(isBlank ? previous : rawIndent);
                    blanks.add(isBlank);
                    comments.add(!isBlank && isComment(raw));
                    clauses.add(!isBlank && !isComment(raw) && isClause(raw));
                }
            }
            int n = indents.size();
            int[] indent = new int[n];
            boolean[] blank = new boolean[n];
            boolean[] comment = new boolean[n];
            boolean[] clause = new boolean[n];
            for (int i = 0; i < n; i++) {
                indent[i] = indents.get(i);
                blank[i] = blanks.get(i);
                comment[i] = comments.get(i);
                clause[i] = clauses.get(i);
            }
            return new Outline(indent, blank, comment, clause);
        }

        /** @return 1-based inclusive [start, end] */
        private int[] bounds(int anchorLine, int levels) {
            if (levels == 0) {
                return new int[]{1, total};
            }
            int cursor = anchorLine - 1;
            for (int level = 0; level < levels; level++) {
                int parent = parentOf(cursor);
                if (parent < 0) {
                    break;
                }
                cursor = parent;
            }
            int parentIndent = indent[cursor];
            int end = cursor;
            for (int i = cursor + 1; i < total; i++) {
                if (blank[i] || indent[i] > parentIndent || (indent[i] == parentIndent && clause[i])) {
                    end = i;
                    continue;
                }
                break;
            }
            int start = cursor;
            while (start > 0 && (comment[start - 1] || blank[start - 1])) {
                start--;
            }
            return new int[]{start + 1, end + 1};
        }

        private int parentOf(int index) {
            int target = indent[index];
            for (int i = index - 1; i >= 0; i--) {
                if (blank[i]) {
                    continue;
                }
                if (indent[i] < target) {
                    return i;
                }
            }
            return -1;
        }

        private static int measure(String line) {
            int width = 0;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == ' ') {
                    width++;
                } else if (ch == '\t') {
                    width += TAB_WIDTH;
                } else {
                    break;
                }
            }
            return width;
        }

        private static boolean isComment(String raw) {
            String text = raw.trim();
            return text.startsWith("#") || text.startsWith("//") || text.startsWith("--")
                    || text.startsWith("/*") || text.startsWith("*");
        }

        private static boolean isClause(String raw) {
            String text = raw.trim();
            if (text.startsWith("}") || text.startsWith(")") || text.startsWith("]")) {
                return true;
            }
            if (text.equals("end") || text.equals("fi") || text.equals("endif")) {
                return true;
            }
            return keyword(text, "else") || keyword(text, "elif") || keyword(text, "except")
                    || keyword(text, "finally") || keyword(text, "catch");
        }

        private static boolean keyword(String text, String word) {
            if (!text.startsWith(word)) {
                return false;
            }
            if (text.length() == word.length()) {
                return true;
            }
            char next = text.charAt(word.length());
            return !Character.isLetterOrDigit(next) && next != '_';
        }
    }
}
