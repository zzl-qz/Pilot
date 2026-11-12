package com.pilot.core.tool;

import java.util.ArrayList;
import java.util.List;

/** 逐行读取补丁，只处理文本语法。 */
final class PatchParser {
    enum Kind {
        ADD, DELETE, UPDATE
    }

    record Line(char mark, String text) {
    }

    /** ordered 保留 hunk 内的原始顺序：上下文行落盘时沿用文件里的原文，不被补丁里的版本改写。 */
    record Hunk(String header, List<String> oldLines, int newLineCount, List<Line> ordered, boolean endOfFile) {
    }

    record Operation(Kind kind, String path, String moveTo, List<String> addLines, List<Hunk> hunks) {
    }

    private static final int MAX_OPERATIONS = 64;
    private static final String ADD_FILE = "*** Add File: ";
    private static final String DELETE_FILE = "*** Delete File: ";
    private static final String UPDATE_FILE = "*** Update File: ";
    private static final String MOVE_TO = "*** Move to: ";

    private final List<String> lines;
    private final int end;
    private int index;

    PatchParser(String patch) {
        lines = patch.lines().toList();
        int begin = 0;
        while (begin < lines.size() && lines.get(begin).isBlank()) {
            begin++;
        }
        int last = lines.size() - 1;
        while (last >= 0 && lines.get(last).isBlank()) {
            last--;
        }
        if (begin >= lines.size() || !lines.get(begin).strip().equals("*** Begin Patch")) {
            throw new IllegalArgumentException("第一行必须是 *** Begin Patch");
        }
        if (last <= begin || !lines.get(last).strip().equals("*** End Patch")) {
            throw new IllegalArgumentException("最后一行必须是 *** End Patch");
        }
        index = begin + 1;
        end = last;
    }

    List<Operation> parse() {
        List<Operation> operations = new ArrayList<>();
        while (index < end) {
            String line = lines.get(index++);
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith(ADD_FILE)) {
                operations.add(readAdd(line.substring(ADD_FILE.length()).strip()));
            } else if (line.startsWith(DELETE_FILE)) {
                operations.add(new Operation(Kind.DELETE, line.substring(DELETE_FILE.length()).strip(), null, List.of(),
                    List.of()));
            } else if (line.startsWith(UPDATE_FILE)) {
                operations.add(readUpdate(line.substring(UPDATE_FILE.length()).strip()));
            } else {
                throw new IllegalArgumentException("无法识别的行（第 " + index + " 行）：" + ToolSupport.oneLine(line, 80));
            }
        }
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("补丁没有任何文件操作");
        }
        if (operations.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("单个补丁最多 " + MAX_OPERATIONS + " 个文件操作");
        }
        return operations;
    }

    private Operation readAdd(String path) {
        List<String> content = new ArrayList<>();
        while (index < end && !lines.get(index).startsWith("*** ")) {
            String added = lines.get(index++);
            if (!added.startsWith("+")) {
                throw new IllegalArgumentException("新增文件 " + path + " 的内容行必须以 + 开头（第 " + index + " 行）");
            }
            content.add(added.substring(1));
        }
        return new Operation(Kind.ADD, path, null, content, List.of());
    }

    private Operation readUpdate(String path) {
        String moveTo = null;
        if (index < end && lines.get(index).startsWith(MOVE_TO)) {
            moveTo = lines.get(index++).substring(MOVE_TO.length()).strip();
        }
        List<Hunk> hunks = new ArrayList<>();
        while (hasUpdateLine()) {
            Hunk hunk = readHunk(path);
            if (hunk != null) {
                hunks.add(hunk);
            }
        }
        if (hunks.isEmpty() && moveTo == null) {
            throw new IllegalArgumentException("修改 " + path + " 没有任何 hunk");
        }
        return new Operation(Kind.UPDATE, path, moveTo, List.of(), hunks);
    }

    private Hunk readHunk(String path) {
        String header = null;
        if (lines.get(index).startsWith("@@")) {
            header = lines.get(index++).substring(2).strip();
        }
        List<String> oldLines = new ArrayList<>();
        int newLineCount = 0;
        List<Line> ordered = new ArrayList<>();
        boolean eof = false;
        while (hasUpdateLine() && !lines.get(index).startsWith("@@")) {
            String hunkLine = lines.get(index++);
            if (hunkLine.equals("*** End of File")) {
                eof = true;
                continue;
            }
            char mark = hunkLine.isEmpty() ? ' ' : hunkLine.charAt(0);
            String text = hunkLine.isEmpty() ? "" : hunkLine.substring(1);
            switch (mark) {
                case ' ' -> {
                    oldLines.add(text);
                    newLineCount++;
                }
                case '-' -> oldLines.add(text);
                case '+' -> newLineCount++;
                default -> throw new IllegalArgumentException("修改 " + path + " 的第 " + index + " 行必须以空格、+、- 或 @@ 开头");
            }
            ordered.add(new Line(mark, text));
        }
        // 单独出现的文件末尾标记不构成一个 hunk。
        return header == null && ordered.isEmpty() ? null : new Hunk(header, oldLines, newLineCount, ordered, eof);
    }

    private boolean hasUpdateLine() {
        if (index >= end) {
            return false;
        }
        String line = lines.get(index);
        return !line.startsWith(ADD_FILE) && !line.startsWith(DELETE_FILE) && !line.startsWith(UPDATE_FILE);
    }
}
