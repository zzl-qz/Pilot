package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void readsRequestedLineRangeAndReportsFileBounds() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "one\ntwo\nthree\nfour\nfive");

        String result = tool.execute(Map.of("path", "App.java", "start_line", 3, "line_count", 2));

        assertEquals("3 | three\n4 | four\n… 显示第 3 到第 4 行，文件共 5 行，后续内容未显示，从第 5 行继续 …", result);
    }

    @Test
    void rejectsInvalidLineRange() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "one");

        assertTrue(tool.execute(Map.of("path", "App.java", "start_line", 0))
                .startsWith("ERROR: 参数 start_line"));
        assertTrue(tool.execute(Map.of("path", "App.java", "line_count", 1.5))
                .startsWith("ERROR: 参数 line_count"));
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
        Files.write(workspace.resolve("logo.bin"), new byte[]{1, 0, 2, 0});

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
        assertEquals("请求范围从第 9 行开始，但文件共 5 行（无内容返回）",
                tool.execute(Map.of("path", "App.java", "start_line", 9)));
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

        assertTrue(result.startsWith("   1 | L1\n"), result);
        assertTrue(result.contains("\n2000 | L2000\n"), result);
        assertFalse(result.contains("L2001"), result);
        assertTrue(result.endsWith("… 显示第 1 到第 2000 行，文件共 100001 行，后续内容未显示，从第 2001 行继续 …"), result);
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
    void readsEnclosingBlockAndOuterLevel() throws IOException {
        Files.writeString(workspace.resolve("Demo.java"), """
                fn outer() {
                    if cond {
                        inner();
                    }
                    tail();
                }
                """);

        String block = tool.execute(Map.of("path", "Demo.java", "anchor_line", 3));
        assertEquals("""
                2 |     if cond {
                3 |         inner();
                4 |     }
                … 显示第 2 到第 4 行，含第 3 行所在代码块，文件共 6 行，后续内容未显示，从第 5 行继续 …""", block);

        String outer = tool.execute(Map.of("path", "Demo.java", "anchor_line", 3, "block_levels", 2));
        assertTrue(outer.startsWith("1 | fn outer() {\n"), outer);
        assertTrue(outer.contains("6 | }\n"), outer);
        assertTrue(outer.endsWith("… 显示第 1 到第 6 行，含第 3 行所在代码块，文件共 6 行 …"), outer);
    }

    @Test
    void readsPythonBranchAsOneBlock() throws IOException {
        Files.writeString(workspace.resolve("demo.py"), """
                if cond:
                    inner()
                else:
                    other()
                next()
                """);

        String block = tool.execute(Map.of("path", "demo.py", "anchor_line", 2));

        assertTrue(block.startsWith("1 | if cond:\n"), block);
        assertTrue(block.contains("4 |     other()\n"), block);
        assertFalse(block.contains("next()"), block);
        assertTrue(block.endsWith("… 显示第 1 到第 4 行，含第 2 行所在代码块，文件共 5 行，后续内容未显示，从第 5 行继续 …"),
                block);
    }
}
