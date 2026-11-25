package com.pilot.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.workspace.PathValidator;

class ReadFileToolTest {
    @TempDir
    Path workspace;

    private ReadFileTool tool;

    @BeforeEach
    void setUp() {
        tool = new ReadFileTool(new PathValidator(workspace));
    }

    @Test
    void returnsContentWithLineNumbers() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "class App {\n  int x = 1;\n}\n");

        String result = tool.execute(Map.of("path", "App.java"));

        assertEquals("1 | class App {\n2 |   int x = 1;\n3 | }\n… 显示第 1 到第 3 行，文件共 3 行 …", result);
    }

    @Test
    void secondReadOfAnUnchangedFileDoesNotRepeatTheBody() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "class App {}\n");
        ReadFileTool tracked = new ReadFileTool(new PathValidator(workspace), new ReadTracker());

        assertTrue(tracked.execute(Map.of("path", "App.java")).contains("class App"));
        assertTrue(tracked.execute(Map.of("path", "App.java")).contains("未变化"));
        Files.writeString(workspace.resolve("App.java"), "class Changed {}\n");
        assertTrue(tracked.execute(Map.of("path", "App.java")).contains("class Changed"));
    }

    @Test
    void editInvalidatesTheReadDedup() throws IOException {
        ReadTracker tracker = new ReadTracker();
        PathValidator paths = new PathValidator(workspace);
        ReadFileTool tracked = new ReadFileTool(paths, tracker);
        EditFileTool editor = new EditFileTool(paths, SnapshotSink.none(), tracker);
        Files.writeString(workspace.resolve("App.java"), "class App {}\n");

        tracked.execute(Map.of("path", "App.java"));
        assertTrue(tracked.execute(Map.of("path", "App.java")).contains("未变化"));
        String edited =
            editor.execute(Map.of("path", "App.java", "old_string", "class App {}", "new_string", "class Changed {}"));
        assertFalse(edited.startsWith("ERROR"), edited);
        assertTrue(tracked.execute(Map.of("path", "App.java")).contains("class Changed"));
    }

    @Test
    void largeFileSequentialPaginationPastThreeSlicesAlwaysSucceeds() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 1200; i++) {
            body.append("line-").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("Large.java"), body.toString());
        ReadFileTool tracked = new ReadFileTool(new PathValidator(workspace), new ReadTracker());

        assertTrue(
            tracked.execute(Map.of("path", "Large.java", "start_line", 1, "line_count", 200)).contains("line-1"));
        assertTrue(
            tracked.execute(Map.of("path", "Large.java", "start_line", 201, "line_count", 200)).contains("line-201"));
        assertTrue(
            tracked.execute(Map.of("path", "Large.java", "start_line", 401, "line_count", 200)).contains("line-401"));
        String fourth = tracked.execute(Map.of("path", "Large.java", "start_line", 601, "line_count", 200));
        String fifth = tracked.execute(Map.of("path", "Large.java", "start_line", 801, "line_count", 200));
        String sixth = tracked.execute(Map.of("path", "Large.java", "start_line", 1001, "line_count", 200));

        assertTrue(fourth.contains("line-601"), fourth);
        assertFalse(fourth.startsWith("文件未变化"), fourth);
        assertTrue(fifth.contains("line-801"), fifth);
        assertTrue(sixth.contains("line-1001"), sixth);
    }

    @Test
    void highOverlapRereadIsDedupedWhileSmallOverlapAndCanonicalPathWork() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            body.append("row-").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("App.java"), body.toString());
        ReadTracker tracker = new ReadTracker();
        ReadFileTool tracked = new ReadFileTool(new PathValidator(workspace), tracker);

        assertTrue(tracked.execute(Map.of("path", "App.java", "start_line", 1, "line_count", 200)).contains("row-1"));
        // 85%+ 重叠且换一种相对路径写法，仍应命中去重并提示已交付区间
        String overlap = tracked.execute(Map.of("path", "./App.java", "start_line", 15, "line_count", 190));
        assertTrue(overlap.startsWith("文件未变化"), overlap);
        assertTrue(overlap.contains("1-200"), overlap);

        // 合法的 10% 上下文衔接分页应放行
        String next = tracked.execute(Map.of("path", "App.java", "start_line", 181, "line_count", 200));
        assertTrue(next.contains("row-380"), next);
        assertFalse(next.startsWith("文件未变化"), next);

        // 上下文压缩触发视图失效后允许重读 1-200
        tracker.invalidateDeliveredViews();
        String rereadAfterCompact = tracked.execute(Map.of("path", "App.java", "start_line", 1, "line_count", 200));
        assertTrue(rereadAfterCompact.contains("row-1"), rereadAfterCompact);
        assertFalse(rereadAfterCompact.startsWith("文件未变化"), rereadAfterCompact);
    }

    @Test
    void repeatedTinySlicesSucceedWithGentleHint() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            body.append("tiny-").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("Tiny.java"), body.toString());
        ReadFileTool tracked = new ReadFileTool(new PathValidator(workspace), new ReadTracker());

        assertTrue(tracked.execute(Map.of("path", "Tiny.java", "start_line", 1, "line_count", 5)).contains("tiny-1"));
        assertTrue(tracked.execute(Map.of("path", "Tiny.java", "start_line", 6, "line_count", 5)).contains("tiny-6"));
        assertTrue(tracked.execute(Map.of("path", "Tiny.java", "start_line", 11, "line_count", 5)).contains("tiny-11"));
        String fourth = tracked.execute(Map.of("path", "Tiny.java", "start_line", 16, "line_count", 5));

        assertTrue(fourth.contains("tiny-16"), fourth);
        assertTrue(fourth.contains("建议增大 line_count"), fourth);
    }

    @Test
    void creatingAFileAllowsReadingAgain() throws IOException {
        ReadTracker tracker = new ReadTracker();
        PathValidator paths = new PathValidator(workspace);
        ReadFileTool tracked = new ReadFileTool(paths, tracker);
        Files.writeString(workspace.resolve("App.java"), "class App {}\n");
        assertTrue(tracked.execute(Map.of("path", "App.java")).contains("class App"));

        String created = new CreateFileTool(paths, SnapshotSink.none(), tracker)
            .execute(Map.of("path", "New.java", "content", "class New {}\n"));
        assertTrue(created.contains("已创建"), created);
        assertTrue(tracked.execute(Map.of("path", "New.java")).contains("class New"));
    }

    @Test
    void readsRequestedLineRangeAndReportsFileBounds() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "one\ntwo\nthree\nfour\nfive");

        String result = tool.execute(Map.of("path", "App.java", "start_line", 3, "line_count", 2));

        assertEquals("3 | three\n4 | four\n… 显示第 3 到第 4 行，文件共 5 行，后续内容未显示，从第 5 行继续 …", result);
    }

    @Test
    void rejectsInvalidLineRange() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "one");

        assertTrue(tool.execute(Map.of("path", "App.java", "start_line", 0)).startsWith("ERROR: 参数 start_line"));
        assertTrue(tool.execute(Map.of("path", "App.java", "line_count", 1.5)).startsWith("ERROR: 参数 line_count"));
    }

    @Test
    void reportsMissingFile() {
        String result = tool.execute(Map.of("path", "missing.txt"));

        assertTrue(result.startsWith("ERROR: 文件不存在"), result);
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        String result = tool.execute(Map.of("path", "../../etc/passwd"));

        assertTrue(result.startsWith("ERROR: 路径超出工作区"), result);
    }

    @Test
    void rejectsDirectory() throws IOException {
        Files.createDirectories(workspace.resolve("src"));

        String result = tool.execute(Map.of("path", "src"));

        assertTrue(result.startsWith("ERROR: 不是普通文件"), result);
    }

    @Test
    void reportsBinaryFile() throws IOException {
        Files.write(workspace.resolve("logo.bin"), new byte[] {1, 0, 2, 0});

        String result = tool.execute(Map.of("path", "logo.bin"));

        assertTrue(result.startsWith("ERROR: 二进制文件"), result);
    }

    @Test
    void requiresPathArgument() {
        assertEquals("ERROR: 缺少参数 path", tool.execute(Map.of()));
    }

    @Test
    void acceptsNumericStringsAndReportsPastEnd() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "one\r\ntwo\r\nthree\r\nfour\r\nfive");

        String result = tool.execute(Map.of("path", "App.java", "start_line", "3", "line_count", "2"));

        assertEquals("3 | three\n4 | four\n… 显示第 3 到第 4 行，文件共 5 行，后续内容未显示，从第 5 行继续 …", result);
        assertEquals("请求范围从第 9 行开始，但文件共 5 行（无内容返回）", tool.execute(Map.of("path", "App.java", "start_line", 9)));
    }

    @Test
    void readsPastFormerByteCeilingByLine() throws IOException {
        Path big = workspace.resolve("big.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(big, StandardCharsets.UTF_8)) {
            for (int i = 1; i <= 3000; i++) {
                writer.write("L");
                writer.write(Integer.toString(i));
                writer.write(' ');
                writer.write("x".repeat(200));
                writer.write('\n');
            }
        }

        assertTrue(Files.size(big) > 512 * 1024);
        String result = tool.execute(Map.of("path", "big.txt", "start_line", 1800, "line_count", 1));

        assertTrue(result.startsWith("1800 | L1800 "), result);
        assertTrue(result.contains("… 显示第 1800 到第 1800 行，文件共 3000 行，后续内容未显示，从第 1801 行继续 …"), result);
        assertFalse(result.contains("文件过大"), result);
    }

    @Test
    void truncatesLongLineAndKeepsFollowingLine() throws IOException {
        Files.writeString(workspace.resolve("long.txt"), "a".repeat(2500) + "\ntail\n");

        String result = tool.execute(Map.of("path", "long.txt"));

        assertTrue(result.startsWith("1 | " + "a".repeat(2000) + "…（此行已截断）\n2 | tail\n"), result);
        assertTrue(result.contains("… 显示第 1 到第 2 行，文件共 2 行 …"), result);
    }

    @Test
    void stopsCountingAfterLineCap() throws IOException {
        Path many = workspace.resolve("many.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(many, StandardCharsets.UTF_8)) {
            for (int i = 1; i <= 100_001; i++) {
                writer.write('L');
                writer.write(Integer.toString(i));
                writer.write('\n');
            }
        }

        String result = tool.execute(Map.of("path", "many.txt"));

        assertTrue(result.startsWith("  1 | L1\n"), result);
        assertTrue(result.contains("\n400 | L400\n"), result);
        assertFalse(result.contains("L401"), result);
        assertTrue(result.endsWith("… 显示第 1 到第 400 行，文件共 100001 行，后续内容未显示，从第 401 行继续 …"), result);
    }

    @Test
    void stopsCountingAfterByteCap() throws IOException {
        Path huge = workspace.resolve("huge.txt");
        String line = "y".repeat(1000);
        try (BufferedWriter writer = Files.newBufferedWriter(huge, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 9000; i++) {
                writer.write(line);
                writer.write('\n');
            }
        }

        assertTrue(Files.size(huge) > 8L * 1024 * 1024);
        String result = tool.execute(Map.of("path", "huge.txt", "line_count", 1));

        assertTrue(result.startsWith("1 | " + line + "\n"), result);
        assertTrue(result.endsWith("… 显示第 1 到第 1 行，文件共 9000 行，后续内容未显示，从第 2 行继续 …"), result);
        assertFalse(result.contains("文件过大"), result);
    }

    @Test
    void readsFromTheEnd() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "one\ntwo\nthree\nfour\nfive");

        assertEquals("5 | five\n… 显示第 5 到第 5 行，文件共 5 行 …",
            tool.execute(Map.of("path", "App.java", "start_line", -1, "line_count", 1)));
        assertEquals("3 | three\n4 | four\n… 显示第 3 到第 4 行，文件共 5 行，后续内容未显示，从第 5 行继续 …",
            tool.execute(Map.of("path", "App.java", "start_line", "-3", "line_count", "2")));
        assertEquals("1 | one\n2 | two\n… 显示第 1 到第 2 行，文件共 5 行（负向起点超出文件开头，已从第 1 行读起），后续内容未显示，从第 3 行继续 …",
            tool.execute(Map.of("path", "App.java", "start_line", -100, "line_count", 2)));
    }

    @Test
    void defaultsToFourHundredLines() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 450; i++) {
            body.append("line ").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("long.txt"), body.toString());

        String result = tool.execute(Map.of("path", "long.txt"));

        assertTrue(result.contains("1 | line 1\n"), result);
        assertTrue(result.contains("400 | line 400\n"), result);
        assertFalse(result.contains("401 | line 401"), result);
        assertTrue(result.contains("从第 401 行继续"), result);
    }

    @Test
    void readingEmptyZeroByteFileRecordsObservationForWriteTools() throws IOException {
        Path empty = Files.createFile(workspace.resolve("__init__.py"));
        ReadTracker tracker = new ReadTracker();
        ReadFileTool trackedRead = new ReadFileTool(new PathValidator(workspace), tracker);

        String output = trackedRead.execute(Map.of("path", "__init__.py"));
        assertTrue(output.contains("空文件"), output);
        assertTrue(tracker.isObserved(empty.toRealPath()), "读取 0 字节文件后必须记录观察态，允许后续编辑/删除");
    }
}
