package com.pilot.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;

class ExactEditTolerantAndFileOpsTest {

    @Test
    void tolerantTrailingWhitespaceAndRelativeIndentReindentsReplacementSafely() {
        String source = """
            public class Demo {
                void run() {
                    int a = 1;  \s
                    if (a > 0) {
                        System.out.println(a);
                    }
                }
            }
            """;

        // 1. 忽略行尾空白唯一命中（带换行，不会退化成精确子串匹配）
        ExactEditBatch.Applied trailing = ExactEditBatch.apply(source,
            List.of(new ExactEditBatch.Edit("        int a = 1;\n", "        int a = 2;\n")));
        assertEquals(1, trailing.tolerantMatches());
        assertTrue(trailing.content().contains("        int a = 2;\n"));

        // 2. 整体缩进少 4 格仍唯一命中，且 new_string 按原文件缩进自动重排
        ExactEditBatch.Applied reindented = ExactEditBatch.apply(source, List.of(new ExactEditBatch.Edit(
            "    if (a > 0) {\n        System.out.println(a);\n    }",
            "    if (a >= 0) {\n        System.out.println(a + 1);\n        return;\n    }")));
        assertEquals(1, reindented.tolerantMatches());
        assertTrue(reindented.content().contains(
            "        if (a >= 0) {\n            System.out.println(a + 1);\n            return;\n        }\n"));
    }

    @Test
    void tolerantMatchStillRejectsAmbiguousMultiHitBlocks() {
        String duplicated = """
            class Dup {
                void one() {
                    step();
                }
                void two() {
                    step();
                }
            }
            """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ExactEditBatch.apply(duplicated, List.of(new ExactEditBatch.Edit("  step();\n", "  next();\n"))));
        assertTrue(ex.getMessage().contains("匹配到多处"));
    }

    @Test
    void tolerantMatchPreservesCrlfAndUtf8Bom() {
        String original = "\uFEFFclass A {\r\n    int x = 1;  \r\n}\r\n";
        ExactEditBatch.Applied applied = ExactEditBatch.apply(original,
            List.of(new ExactEditBatch.Edit("  int x = 1;\n", "  int x = 42;\n")));
        assertEquals("\uFEFFclass A {\r\n    int x = 42;\r\n}\r\n", applied.content());
    }

    @Test
    void deleteFileEnforcesReadGuardSnapshotsAndSafetyBoundaries(@TempDir Path root) throws Exception {
        Path file = root.resolve("old.txt");
        Files.writeString(file, "legacy", StandardCharsets.UTF_8);
        Path dir = root.resolve("sub");
        Files.createDirectories(dir);

        PathValidator paths = new PathValidator(root);
        ReadTracker reads = new ReadTracker();
        List<Path> snapshotted = new ArrayList<>();
        SnapshotSink snapshots = snapshotted::add;
        DeleteFileTool deleteTool = new DeleteFileTool(paths, snapshots, reads);

        // 未先 read_file 拒绝删除
        assertTrue(deleteTool.execute(Map.of("path", "old.txt")).startsWith("ERROR: 本次运行尚未读取过"));
        assertTrue(Files.exists(file));

        // 拒绝目录与越界路径
        assertTrue(deleteTool.execute(Map.of("path", "sub")).contains("仅支持删除普通文件"));
        assertTrue(deleteTool.execute(Map.of("path", "../escape.txt")).startsWith("ERROR:"));

        // 读取后正常删除，并写入快照
        reads.observed(paths.resolve("old.txt"));
        ToolResult ok = deleteTool.executeResult(Map.of("path", "old.txt"));
        assertFalse(ok.text().startsWith("ERROR:"));
        assertFalse(Files.exists(file));
        assertEquals(List.of("old.txt"), ok.changedPaths());
        assertEquals(List.of(paths.resolve("old.txt").normalize()),
            snapshotted.stream().map(Path::normalize).toList());
    }

    @Test
    void moveFileCreatesParentsProtectsOverwriteAndRecordsBothPaths(@TempDir Path root) throws Exception {
        Path src = root.resolve("a.txt");
        Files.writeString(src, "hello", StandardCharsets.UTF_8);
        Path existing = root.resolve("existing.txt");
        Files.writeString(existing, "keep", StandardCharsets.UTF_8);

        PathValidator paths = new PathValidator(root);
        ReadTracker reads = new ReadTracker();
        List<Path> snapshotted = new ArrayList<>();
        MoveFileTool moveTool = new MoveFileTool(paths, snapshotted::add, reads);

        // 默认不覆盖已存在文件
        assertTrue(moveTool.execute(Map.of("source", "a.txt", "destination", "existing.txt")).contains("目标文件已存在"));
        assertTrue(Files.exists(src));

        // 移动到深层目录（自动建父目录）
        ToolResult moved = moveTool.executeResult(Map.of("source", "a.txt", "destination", "nested/dir/b.txt"));
        assertFalse(moved.text().startsWith("ERROR:"));
        assertFalse(Files.exists(src));
        assertEquals("hello", Files.readString(root.resolve("nested/dir/b.txt"), StandardCharsets.UTF_8));
        assertEquals(List.of("a.txt", "nested/dir/b.txt"), moved.changedPaths());
        assertTrue(reads.isObserved(paths.resolve("nested/dir/b.txt")));
        assertEquals(2, snapshotted.size());

        // 显式 overwrite=true 允许覆盖
        ToolResult overwritten = moveTool.executeResult(
            Map.of("source", "nested/dir/b.txt", "destination", "existing.txt", "overwrite", true));
        assertFalse(overwritten.text().startsWith("ERROR:"));
        assertEquals("hello", Files.readString(existing, StandardCharsets.UTF_8));
    }
}
