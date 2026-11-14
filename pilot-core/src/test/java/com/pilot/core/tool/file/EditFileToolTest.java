package com.pilot.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.workspace.PathValidator;

class EditFileToolTest {
    @TempDir
    Path workspace;

    private EditFileTool tool;

    @BeforeEach
    void setUp() {
        tool = new EditFileTool(new PathValidator(workspace));
    }

    @Test
    void missingOldStringIncludesClosestLines() throws IOException {
        Files.writeString(workspace.resolve("sample.txt"), "header\n  useful target line\nfooter\n");

        String result =
            tool.execute(Map.of("path", "sample.txt", "old_string", "useful target lnie", "new_string", "replacement"));

        assertTrue(result.contains("最接近的文件片段"), result);
        assertTrue(result.contains("2:   useful target line"), result);
    }

    @Test
    void replacesUniqueTailInFileLargerThanFormerCeiling() throws IOException {
        Path file = workspace.resolve("big.txt");
        byte[] prefix = "a".repeat(512 * 1024).getBytes(StandardCharsets.UTF_8);
        Files.write(file, prefix);
        Files.writeString(file, "\nUNIQUE_TAIL\n", StandardOpenOption.APPEND);

        String result = tool.execute(Map.of("path", "big.txt", "old_string", "UNIQUE_TAIL", "new_string", "DONE"));

        assertTrue(result.startsWith("已修改 "), result);
        String updated = Files.readString(file);
        assertTrue(updated.endsWith("\nDONE\n"), updated.substring(updated.length() - 20));
        assertEquals(-1, updated.indexOf("UNIQUE_TAIL"));
    }

    @Test
    void editingToolsShareTheSizeAndBinaryChecks() throws IOException {
        Path file = workspace.resolve("共享 样本.txt");
        var paths = new PathValidator(workspace);
        var sandbox = new SandboxEditFileTool(paths, SandboxSink.rejectAll());
        var patch = new ApplyPatchTool(paths, SnapshotSink.none(), ReadGuard.allowAll());
        var replacement = Map.<String, Object>of("path", file.getFileName().toString(), "old_string", "before",
            "new_string", "after");
        var patchArgs = Map.<String, Object>of("patch", """
            *** Begin Patch
            *** Update File: 共享 样本.txt
            @@
            -before
            +after
            *** End Patch
            """);

        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {1}), EditableText.MAX_BYTES);
        }
        assertEquals("ERROR: 文件过大（上限 32MB），无法整份替换。", tool.execute(replacement));
        assertEquals(tool.execute(replacement), sandbox.execute(replacement));
        assertTrue(patch.execute(patchArgs).contains("文件过大（上限 32MB）"));
        assertEquals(EditableText.MAX_BYTES + 1L, Files.size(file));

        byte[] binary = {1, 0, 2};
        Files.write(file, binary);
        assertTrue(tool.execute(replacement).startsWith("ERROR: 二进制文件，无法编辑"));
        assertEquals(tool.execute(replacement), sandbox.execute(replacement));
        assertTrue(patch.execute(patchArgs).startsWith("ERROR: 二进制文件，无法修改"));
        assertArrayEquals(binary, Files.readAllBytes(file));
    }

    /** 先读后改是机制约束：本次运行没读过的文件拒绝编辑，错误信息要能指导下一步。 */
    @Test
    void rejectsEditingUnreadFile() throws IOException {
        Path file = workspace.resolve("sample.txt");
        Files.writeString(file, "header\ntarget line\nfooter\n");
        ReadTracker reads = new ReadTracker();
        EditFileTool guarded = new EditFileTool(new PathValidator(workspace), SnapshotSink.none(), reads);

        String denied =
            guarded.execute(Map.of("path", "sample.txt", "old_string", "target line", "new_string", "replaced"));

        assertTrue(denied.startsWith("ERROR: 本次运行尚未读取过"), denied);
        assertTrue(denied.contains("read_file"), denied);
        assertEquals("header\ntarget line\nfooter\n", Files.readString(file), "未读文件不应被改动");

        // read_file 之后放行；错误信息里教的补救动作真实有效
        ReadFileTool reader = new ReadFileTool(new PathValidator(workspace), reads);
        assertTrue(reader.execute(Map.of("path", "sample.txt")).contains("target line"));
        String allowed =
            guarded.execute(Map.of("path", "sample.txt", "old_string", "target line", "new_string", "replaced"));
        assertTrue(allowed.startsWith("已修改 "), allowed);
    }

    @Test
    void outOfRangeReadDoesNotUnlockEditing() throws IOException {
        Path file = workspace.resolve("sample.txt");
        Files.writeString(file, "target line\n");
        PathValidator paths = new PathValidator(workspace);
        ReadTracker reads = new ReadTracker();
        ReadFileTool reader = new ReadFileTool(paths, reads);
        EditFileTool editor = new EditFileTool(paths, SnapshotSink.none(), reads);
        Map<String, Object> edit =
            Map.<String, Object>of("path", "sample.txt", "old_string", "target line", "new_string", "replaced");

        assertTrue(reader.execute(Map.of("path", "sample.txt", "start_line", 9999)).contains("无内容返回"));
        assertFalse(reads.isObserved(file.toRealPath()), "未返回内容的读取不能解锁编辑");
        assertTrue(editor.execute(edit).startsWith("ERROR: 本次运行尚未读取过"));
        assertEquals("target line\n", Files.readString(file));

        assertTrue(reader.execute(Map.of("path", "sample.txt", "start_line", -1)).contains("target line"));
        assertTrue(editor.execute(edit).startsWith("已修改 "));
        assertEquals("replaced\n", Files.readString(file));
    }

    /** 刚创建或刚编辑过的文件视为已读：连续编辑不被误伤。 */
    @Test
    void treatsJustWrittenFileAsObserved() throws IOException {
        ReadTracker reads = new ReadTracker();
        CreateFileTool creator = new CreateFileTool(new PathValidator(workspace), SnapshotSink.none(), reads);
        EditFileTool editor = new EditFileTool(new PathValidator(workspace), SnapshotSink.none(), reads);

        assertTrue(creator.execute(Map.of("path", "fresh.txt", "content", "one\ntwo\n")).startsWith("已创建 "));

        String edited = editor.execute(Map.of("path", "fresh.txt", "old_string", "two", "new_string", "TWO"));
        assertTrue(edited.startsWith("已修改 "), edited);

        // 编辑过一次之后，紧接着再改也不需要重读
        String again = editor.execute(Map.of("path", "fresh.txt", "old_string", "TWO", "new_string", "2"));
        assertTrue(again.startsWith("已修改 "), again);
    }

    /** allowAll 是测试与未装配场景的默认值：行为与改造前完全一致。 */
    @Test
    void allowAllGuardKeepsLegacyBehavior() throws IOException {
        Files.writeString(workspace.resolve("legacy.txt"), "before\n");

        String result = new EditFileTool(new PathValidator(workspace))
            .execute(Map.of("path", "legacy.txt", "old_string", "before", "new_string", "after"));

        assertTrue(result.startsWith("已修改 "), result);
    }

    @Test
    void appliesDisjointEditsAgainstOriginalFileInOneWrite() throws IOException {
        Path file = workspace.resolve("sample.txt");
        Files.writeString(file, "\uFEFFfirst\r\nkeep\r\nlast\r\n");

        String result = tool
            .execute(Map.of("path", "sample.txt", "edits", List.of(Map.of("old_string", "last", "new_string", "end"),
                Map.of("old_string", "first", "new_string", "start"))));

        assertTrue(result.contains("替换 2 处"), result);
        assertEquals("\uFEFFstart\r\nkeep\r\nend\r\n", Files.readString(file));
    }

    @Test
    void rejectedBatchLeavesFileUntouched() throws IOException {
        Path file = workspace.resolve("sample.txt");
        String original = "header\nvalue\nfooter\n";
        Files.writeString(file, original);

        String missing = tool.execute(
            Map.of("path", "sample.txt", "edits", List.of(Map.of("old_string", "header", "new_string", "title"),
                Map.of("old_string", "absent", "new_string", "other"))));
        assertTrue(missing.contains("edits[1] 未找到"), missing);
        assertEquals(original, Files.readString(file));

        String overlap = tool.execute(
            Map.of("path", "sample.txt", "edits", List.of(Map.of("old_string", "header\nvalue", "new_string", "first"),
                Map.of("old_string", "value", "new_string", "second"))));
        assertTrue(overlap.contains("重叠或嵌套"), overlap);
        assertEquals(original, Files.readString(file));
    }
}
