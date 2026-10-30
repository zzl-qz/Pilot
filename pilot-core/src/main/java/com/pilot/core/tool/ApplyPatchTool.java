package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 跨文件补丁：一次提交多个文件的新增、修改、删除与重命名。
 *
 * <p>格式与主流编码模型训练时见过的 apply_patch 一致：
 * <pre>
 * *** Begin Patch
 * *** Update File: src/A.java
 * @@ class A
 *  上下文行
 * -旧行
 * +新行
 * *** Add File: src/B.java
 * +新文件内容
 * *** Delete File: src/Old.java
 * *** End Patch
 * </pre>
 *
 * <p>与 {@link EditFileTool} 同样的硬约束：修改和删除前必须读过该文件；每个 hunk 必须在文件里唯一命中，
 * 不自动选择第一处；整份补丁先在内存中全部校验通过，再统一落盘，校验失败不产生任何改动。
 */
public final class ApplyPatchTool implements Tool, WritesFiles {
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final int MAX_OPERATIONS = 64;

    private final PathValidator paths;
    private final SnapshotSink snapshots;
    private final ReadGuard reads;

    public ApplyPatchTool(PathValidator paths, SnapshotSink snapshots, ReadGuard reads) {
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("patch", Map.of("type", "string", "description",
                        "完整补丁文本，以 *** Begin Patch 开头、*** End Patch 结尾")),
                "required", List.of("patch"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("patch");
        if (!(raw instanceof String patch) || patch.isBlank()) {
            return ToolResult.text("ERROR: 缺少参数 patch");
        }
        List<Operation> operations;
        try {
            operations = parse(patch);
        } catch (IllegalArgumentException e) {
            return ToolResult.text("ERROR: 补丁格式错误：" + e.getMessage());
        }
        List<Planned> plan = new ArrayList<>();
        try {
            plan = plan(operations);
        } catch (IllegalArgumentException | PatchException e) {
            return ToolResult.text("ERROR: " + e.getMessage() + "。补丁未应用，文件均未修改。");
        } catch (RuntimeException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.text("ERROR: 读取失败: " + e.getMessage() + "。补丁未应用，文件均未修改。");
        }

        List<String> applied = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        try {
            for (Planned step : plan) {
                write(step);
                applied.add(step.summary());
                changed.addAll(step.changed());
            }
        } catch (IOException | RuntimeException e) {
            String done = applied.isEmpty() ? "没有文件被修改" : "已写入：" + String.join("；", applied);
            return new ToolResult("ERROR: 写入失败: " + e.getMessage() + "。" + done
                    + "。请 read_file 核对当前状态后再继续。", List.copyOf(changed), List.of());
        }
        return new ToolResult("已应用补丁（" + plan.size() + " 个文件）：\n" + String.join("\n", applied),
                List.copyOf(changed), List.of());
    }

    @Override
    public String changedPath(ToolCall call) {
        return null;
    }

    // ---- 解析 ----

    private enum Kind { ADD, DELETE, UPDATE }

    private record Line(char mark, String text) { }

    /** ordered 保留 hunk 内的原始顺序：上下文行落盘时沿用文件里的原文，不被补丁里的版本改写。 */
    private record Hunk(String header, List<String> oldLines, List<String> newLines, List<Line> ordered,
                        boolean endOfFile) { }

    private record Operation(Kind kind, String path, String moveTo, List<String> addLines, List<Hunk> hunks) { }

    static List<Operation> parse(String patch) {
        List<String> lines = patch.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        int begin = 0;
        while (begin < lines.size() && lines.get(begin).isBlank()) begin++;
        int end = lines.size() - 1;
        while (end >= 0 && lines.get(end).isBlank()) end--;
        if (begin >= lines.size() || !lines.get(begin).strip().equals("*** Begin Patch")) {
            throw new IllegalArgumentException("第一行必须是 *** Begin Patch");
        }
        if (end <= begin || !lines.get(end).strip().equals("*** End Patch")) {
            throw new IllegalArgumentException("最后一行必须是 *** End Patch");
        }
        List<Operation> operations = new ArrayList<>();
        int index = begin + 1;
        while (index < end) {
            String line = lines.get(index);
            if (line.isBlank()) {
                index++;
            } else if (line.startsWith("*** Add File: ")) {
                String path = line.substring("*** Add File: ".length()).strip();
                List<String> content = new ArrayList<>();
                index++;
                while (index < end && !lines.get(index).startsWith("*** ")) {
                    String added = lines.get(index);
                    if (!added.startsWith("+")) {
                        throw new IllegalArgumentException("新增文件 " + path + " 的内容行必须以 + 开头（第 "
                                + (index + 1) + " 行）");
                    }
                    content.add(added.substring(1));
                    index++;
                }
                operations.add(new Operation(Kind.ADD, path, null, content, List.of()));
            } else if (line.startsWith("*** Delete File: ")) {
                operations.add(new Operation(Kind.DELETE, line.substring("*** Delete File: ".length()).strip(),
                        null, List.of(), List.of()));
                index++;
            } else if (line.startsWith("*** Update File: ")) {
                String path = line.substring("*** Update File: ".length()).strip();
                String moveTo = null;
                index++;
                if (index < end && lines.get(index).startsWith("*** Move to: ")) {
                    moveTo = lines.get(index).substring("*** Move to: ".length()).strip();
                    index++;
                }
                List<Hunk> hunks = new ArrayList<>();
                String header = null;
                List<String> oldLines = new ArrayList<>();
                List<String> newLines = new ArrayList<>();
                List<Line> ordered = new ArrayList<>();
                boolean open = false;
                boolean eof = false;
                while (index < end && !isOperationStart(lines.get(index))) {
                    String hunkLine = lines.get(index);
                    if (hunkLine.startsWith("@@")) {
                        if (open) hunks.add(new Hunk(header, oldLines, newLines, ordered, eof));
                        header = hunkLine.substring(2).strip();
                        oldLines = new ArrayList<>();
                        newLines = new ArrayList<>();
                        ordered = new ArrayList<>();
                        eof = false;
                        open = true;
                    } else if (hunkLine.equals("*** End of File")) {
                        eof = true;
                    } else {
                        char mark = hunkLine.isEmpty() ? ' ' : hunkLine.charAt(0);
                        String text = hunkLine.isEmpty() ? "" : hunkLine.substring(1);
                        switch (mark) {
                            case ' ' -> { oldLines.add(text); newLines.add(text); ordered.add(new Line(' ', text)); }
                            case '-' -> { oldLines.add(text); ordered.add(new Line('-', text)); }
                            case '+' -> { newLines.add(text); ordered.add(new Line('+', text)); }
                            default -> throw new IllegalArgumentException("修改 " + path
                                    + " 的第 " + (index + 1) + " 行必须以空格、+、- 或 @@ 开头");
                        }
                        open = true;
                    }
                    index++;
                }
                if (open) hunks.add(new Hunk(header, oldLines, newLines, ordered, eof));
                if (hunks.isEmpty() && moveTo == null) {
                    throw new IllegalArgumentException("修改 " + path + " 没有任何 hunk");
                }
                operations.add(new Operation(Kind.UPDATE, path, moveTo, List.of(), hunks));
            } else {
                throw new IllegalArgumentException("无法识别的行（第 " + (index + 1) + " 行）：" + ToolSupport.oneLine(line, 80));
            }
        }
        if (operations.isEmpty()) throw new IllegalArgumentException("补丁没有任何文件操作");
        if (operations.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("单个补丁最多 " + MAX_OPERATIONS + " 个文件操作");
        }
        return operations;
    }

    private static boolean isOperationStart(String line) {
        return line.startsWith("*** Add File: ") || line.startsWith("*** Delete File: ")
                || line.startsWith("*** Update File: ");
    }

    // ---- 校验与生成新内容（内存中，不落盘） ----

    private static final class PatchException extends RuntimeException {
        PatchException(String message) {
            super(message);
        }
    }

    private record Planned(Kind kind, Path file, Path target, String content, String shown, String summary,
                           List<String> changed) { }

    private List<Planned> plan(List<Operation> operations) throws IOException {
        Map<String, Operation> seen = new LinkedHashMap<>();
        List<Planned> plan = new ArrayList<>();
        for (Operation op : operations) {
            Path file = paths.resolve(op.path());
            String shown = paths.display(file);
            if (seen.put(shown, op) != null) throw new PatchException("同一文件在补丁中出现多次：" + shown);
            switch (op.kind()) {
                case ADD -> {
                    if (Files.exists(file)) throw new PatchException("文件已存在: " + shown + "，修改请用 Update File");
                    String content = String.join("\n", op.addLines()) + (op.addLines().isEmpty() ? "" : "\n");
                    plan.add(new Planned(Kind.ADD, file, null, content, shown,
                            "新增 " + shown + "（" + op.addLines().size() + " 行）", List.of(shown)));
                }
                case DELETE -> {
                    requireExistingFile(file, shown);
                    requireObserved(file, shown);
                    plan.add(new Planned(Kind.DELETE, file, null, null, shown, "删除 " + shown, List.of(shown)));
                }
                case UPDATE -> plan.add(planUpdate(op, file, shown));
            }
        }
        return plan;
    }

    private Planned planUpdate(Operation op, Path file, String shown) throws IOException {
        requireExistingFile(file, shown);
        requireObserved(file, shown);
        if (Files.size(file) > MAX_BYTES) throw new PatchException("文件过大（上限 32MB）: " + shown);
        byte[] bytes = Files.readAllBytes(file);
        if (ToolSupport.isBinary(bytes)) throw new PatchException("二进制文件，无法修改: " + shown);
        String original = new String(bytes, StandardCharsets.UTF_8);
        String bom = original.startsWith("\uFEFF") ? "\uFEFF" : "";
        String body = bom.isEmpty() ? original : original.substring(1);
        String eol = body.contains("\r\n") ? "\r\n" : body.contains("\r") ? "\r" : "\n";
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        boolean trailingNewline = normalized.endsWith("\n");
        List<String> lines = new ArrayList<>(normalized.isEmpty() ? List.of() : List.of(normalized.split("\n", -1)));
        if (trailingNewline) lines.remove(lines.size() - 1);

        int cursor = 0;
        int added = 0;
        int removed = 0;
        for (int i = 0; i < op.hunks().size(); i++) {
            Hunk hunk = op.hunks().get(i);
            int start = cursor;
            if (hunk.header() != null && !hunk.header().isBlank()) {
                int at = indexOfLine(lines, hunk.header(), cursor);
                if (at < 0) {
                    throw new PatchException(shown + " 的 hunk " + (i + 1) + " 找不到定位行 @@ " + hunk.header());
                }
                start = at + 1;
            }
            int position;
            if (hunk.oldLines().isEmpty()) {
                // 纯新增：有定位行就插在它后面，否则追加到文件末尾
                position = hunk.header() != null && !hunk.header().isBlank() ? start : lines.size();
            } else {
                position = locate(lines, hunk, start, shown, i + 1);
            }
            List<String> replacement = new ArrayList<>();
            int oldIndex = 0;
            for (Line line : hunk.ordered()) {
                switch (line.mark()) {
                    case ' ' -> replacement.add(lines.get(position + oldIndex++));
                    case '-' -> oldIndex++;
                    default -> replacement.add(line.text());
                }
            }
            for (int r = 0; r < hunk.oldLines().size(); r++) lines.remove(position);
            lines.addAll(position, replacement);
            cursor = position + replacement.size();
            removed += hunk.oldLines().size();
            added += hunk.newLines().size();
        }
        String text = bom + String.join("\n", lines) + (trailingNewline && !lines.isEmpty() ? "\n" : "");
        text = text.replace("\n", eol);

        Path target = file;
        String targetShown = shown;
        if (op.moveTo() != null && !op.moveTo().isBlank()) {
            target = paths.resolve(op.moveTo());
            targetShown = paths.display(target);
            if (!target.equals(file) && Files.exists(target)) {
                throw new PatchException("重命名目标已存在: " + targetShown);
            }
        }
        boolean moved = !target.equals(file);
        List<String> changed = moved ? List.of(shown, targetShown) : List.of(shown);
        String summary = (moved ? "重命名 " + shown + " → " + targetShown : "修改 " + shown)
                + "（+" + added + " -" + removed + "）";
        return new Planned(Kind.UPDATE, file, moved ? target : null, text, shown, summary, changed);
    }

    /** 唯一命中：先精确，再忽略行尾空白，再忽略首尾空白；同一档位命中多处直接报错。 */
    private static int locate(List<String> lines, Hunk hunk, int from, String shown, int number) {
        List<UnaryOperator<String>> modes = List.of(line -> line, String::stripTrailing, String::strip);
        List<String> wanted = hunk.oldLines();
        for (UnaryOperator<String> mode : modes) {
            if (hunk.endOfFile()) {
                int at = lines.size() - wanted.size();
                if (at >= from && matches(lines, wanted, at, mode)) return at;
                continue;
            }
            int first = -1;
            for (int at = from; at + wanted.size() <= lines.size(); at++) {
                if (!matches(lines, wanted, at, mode)) continue;
                if (first >= 0) {
                    throw new PatchException(shown + " 的 hunk " + number
                            + " 匹配到多处，无法唯一定位。请增加上下文行，或用 @@ 指定所在的函数/类");
                }
                first = at;
            }
            if (first >= 0) return first;
        }
        String firstLine = wanted.getFirst();
        throw new PatchException(shown + " 的 hunk " + number + " 没有找到对应原文。最接近的文件片段：\n"
                + ExactEditBatch.closestExcerpt(String.join("\n", lines), String.join("\n", wanted))
                + "\n请基于该片段重写 hunk（首行：" + ToolSupport.oneLine(firstLine, 80) + "）");
    }

    private static boolean matches(List<String> lines, List<String> wanted, int at, UnaryOperator<String> mode) {
        for (int i = 0; i < wanted.size(); i++) {
            if (!mode.apply(lines.get(at + i)).equals(mode.apply(wanted.get(i)))) return false;
        }
        return true;
    }

    private static int indexOfLine(List<String> lines, String header, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(header.strip())) return i;
        }
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(header.strip())) return i;
        }
        return -1;
    }

    private void requireExistingFile(Path file, String shown) {
        if (!Files.exists(file)) throw new PatchException("文件不存在: " + shown);
        if (!Files.isRegularFile(file)) throw new PatchException("不是普通文件: " + shown);
    }

    private void requireObserved(Path file, String shown) {
        if (!reads.isObserved(file)) {
            throw new PatchException("本次运行尚未读取过 " + shown + "，无法确认原文。先 read_file 查看当前内容");
        }
    }

    // ---- 落盘 ----

    private void write(Planned step) throws IOException {
        switch (step.kind()) {
            case ADD -> {
                Files.createDirectories(step.file().getParent());
                snapshots.beforeWrite(step.file());
                Files.writeString(step.file(), step.content(), StandardCharsets.UTF_8);
                snapshots.afterWrite(step.file());
                reads.observed(step.file());
            }
            case DELETE -> {
                snapshots.beforeWrite(step.file());
                Files.delete(step.file());
            }
            case UPDATE -> {
                if (step.target() == null) {
                    snapshots.beforeWrite(step.file());
                    Files.writeString(step.file(), step.content(), StandardCharsets.UTF_8);
                    snapshots.afterWrite(step.file());
                    reads.observed(step.file());
                } else {
                    Files.createDirectories(step.target().getParent());
                    snapshots.beforeWrite(step.file());
                    snapshots.beforeWrite(step.target());
                    Files.writeString(step.target(), step.content(), StandardCharsets.UTF_8);
                    Files.delete(step.file());
                    snapshots.afterWrite(step.target());
                    reads.observed(step.target());
                }
            }
        }
    }
}
