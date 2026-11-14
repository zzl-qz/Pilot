package com.pilot.core.tool;

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

import com.pilot.core.workspace.PathValidator;

/** 按行读取工作区文本。结果里只有请求的窗口，行号用文件里的绝对行号。 */
public final class ReadFileTool implements Tool.ParallelReadOnly {
    private static final int DEFAULT_LINES = 200;
    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_CHARS = 2000;
    private static final int BINARY_SAMPLE_BYTES = 8000;

    private final PathValidator paths;
    private final ReadGuard reads;
    private final SandboxSink sandbox;

    public ReadFileTool(PathValidator paths) {
        this(paths, ReadGuard.allowAll());
    }

    public ReadFileTool(PathValidator paths, ReadGuard reads) {
        this(paths, reads, null);
    }

    public ReadFileTool(PathValidator paths, ReadGuard reads, SandboxSink sandbox) {
        this.paths = paths;
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public Map<String, Object> parameters() {
        return schema("工作区内的相对文件路径", "起始行号");
    }

    /** 附件读取沿用文本分页和 PDF 参数，调用方指定路径说明。 */
    public static Map<String, Object> schema(String pathDescription, String startLineDescription) {
        return Map.of("type", "object", "properties",
            Map.of("path", Map.of("type", "string", "description", pathDescription), "start_line",
                Map.of("type", "integer", "description", startLineDescription + "。正数从 1 开始，负数从末尾数，-1 是最后一行。默认 1"),
                "line_count", Map.of("type", "integer", "description", "最多读取行数，默认 200，最大 2000"), "pages",
                Map.of("type", "string", "description", "仅 PDF：页码或范围，例如 \"3\" 或 \"2-5\"，默认前 10 页，单次最多 30 页")),
            "required", List.of("path"), "additionalProperties", false);
    }

    /** 图片、PDF 与 Office 文件走媒体/抽取分支，其余沿用文本读取。 */
    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        if (rawPath != null) {
            try {
                Path file = paths.resolve(rawPath);
                if (Files.isRegularFile(file)) {
                    if (MediaFileReader.isImage(file)) {
                        return MediaFileReader.readImage(file, rawPath);
                    }
                    if (MediaFileReader.isPdf(file)) {
                        return MediaFileReader.readPdf(file, rawPath, args.get("pages"));
                    }
                    if (MediaFileReader.isOfficeText(file)) {
                        return MediaFileReader.readOfficeText(file, rawPath);
                    }
                }
            } catch (RuntimeException e) {
                // 路径非法的错误文本交给下面的文本分支统一生成
            } catch (IOException e) {
                return ToolResult.text("ERROR: 读取失败: " + e.getMessage());
            }
        }
        return ToolResult.text(execute(args));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        if (rawPath == null) {
            return "ERROR: 缺少参数 path";
        }
        IntArg startArg = intArg(args, "start_line");
        IntArg countArg = intArg(args, "line_count");
        if (startArg.present() && (startArg.value() == null || startArg.value() == 0)) {
            return "ERROR: 参数 start_line 必须是非 0 整数，正数从 1 开始，负数从末尾数";
        }
        if (countArg.present() && (countArg.value() == null || countArg.value() < 1)) {
            return "ERROR: 参数 line_count 必须是大于等于 1 的整数";
        }
        int startLine = startArg.present() ? startArg.value() : 1;
        int lineCount = Math.min(countArg.present() ? countArg.value() : DEFAULT_LINES, MAX_LINES);
        Path file;
        try {
            file = paths.resolve(rawPath);
            if (sandbox != null) {
                file = paths.resolve(sandbox.canonicalPath(paths.display(file)));
            }
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        String proposal = sandbox == null ? null : sandbox.proposedText(paths.display(file));
        if (!Files.exists(file) && proposal == null) {
            return "ERROR: 文件不存在: " + rawPath;
        }
        if (proposal == null && !Files.isRegularFile(file)) {
            return "ERROR: 不是普通文件: " + rawPath;
        }
        try {
            if (proposal == null && looksBinary(file)) {
                return "ERROR: 二进制文件，无法读取: " + rawPath;
            }
            ReadResult slice =
                proposal == null ? readSlice(file, startLine, lineCount) : readText(proposal, startLine, lineCount);
            // 只有实际返回文件行才解锁编辑，越界读取和空文件提示不算观察过内容。
            if (slice.hasLines()) {
                reads.observed(file);
            }
            return slice.text();
        } catch (IOException e) {
            return "ERROR: 读取失败: " + e.getMessage();
        }
    }

    private static ReadResult readText(String text, int startLine, int lineCount) {
        List<String> all = text.lines()
            .map(line -> line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "…（超长行已截断）" : line)
            .toList();
        int total = all.size();
        if (total == 0) {
            return new ReadResult("(空文件，共 0 行)", false);
        }
        long requested = startLine < 0 ? (long)total + startLine + 1 : startLine;
        int start = (int)Math.max(1, Math.min(total, requested));
        int end = Math.min(total, start + lineCount - 1);
        List<String> selected = all.subList(start - 1, end);
        String note = startLine < 0 && requested < 1 ? "（负向起点超出文件开头，已从第 1 行读起）" : "";
        return new ReadResult(render(start, selected, total, "", note), !selected.isEmpty());
    }

    private static boolean looksBinary(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return ToolSupport.isBinary(in.readNBytes(BINARY_SAMPLE_BYTES));
        }
    }

    private static ReadResult readSlice(Path file, int startLine, int lineCount) throws IOException {
        if (startLine < 0) {
            int total = countLines(file);
            if (total == 0) {
                return new ReadResult("(空文件，共 0 行)", false);
            }
            long resolved = (long)total + startLine + 1;
            boolean clamped = resolved < 1;
            int start = clamped ? 1 : (int)resolved;
            List<String> lines = collect(file, start, lineCount);
            String clampNote = clamped ? "（负向起点超出文件开头，已从第 1 行读起）" : "";
            return new ReadResult(render(start, lines, total, "", clampNote), !lines.isEmpty());
        }
        Slice slice = scanSlice(file, startLine, lineCount);
        if (slice.total == 0) {
            return new ReadResult("(空文件，共 0 行)", false);
        }
        if (slice.lines.isEmpty()) {
            return new ReadResult("请求范围从第 " + startLine + " 行开始，但文件共 " + slice.total + " 行（无内容返回）", false);
        }
        return new ReadResult(render(startLine, slice.lines, slice.total, "", ""), true);
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
                return (int)numeric;
            }
            return null;
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text.strip());
                if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                    return (int)parsed;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private record IntArg(boolean present, Integer value) {
    }

    private record ReadResult(String text, boolean hasLines) {
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
                    text.append((char)ch);
                } else {
                    truncated = true;
                }
            }
        }
    }

}
