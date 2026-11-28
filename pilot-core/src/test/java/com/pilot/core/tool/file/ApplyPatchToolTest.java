package com.pilot.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.ToolCall;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;

class ApplyPatchToolTest {
    @TempDir
    Path workspace;

    private final Set<Path> observed = new HashSet<>();
    private final List<String> snapshots = new ArrayList<>();
    private ApplyPatchTool tool;
    private Path realWorkspace;

    @BeforeEach
    void setUp() throws IOException {
        realWorkspace = workspace.toRealPath();
        ReadGuard guard = new ReadGuard() {
            @Override
            public void observed(Path file) {
                observed.add(file.toAbsolutePath().normalize());
            }

            @Override
            public boolean isObserved(Path file) {
                return observed.contains(file.toAbsolutePath().normalize());
            }
        };
        tool =
            new ApplyPatchTool(new PathValidator(workspace),
                file -> snapshots
                    .add(realWorkspace.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')),
                guard);
    }

    private Path write(String name, String content) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        observed.add(file.toRealPath());
        return file;
    }

    private ToolResult apply(String patch) {
        return tool.executeResult(Map.of("patch", patch));
    }

    @Test
    void rejectsMultipleMovesToTheSameTargetBeforeWriting() throws IOException {
        write("a.txt", "a\n");
        write("c.txt", "c\n");
        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: a.txt
            *** Move to: b.txt
            -a
            +A
            *** Update File: c.txt
            *** Move to: b.txt
            -c
            +C
            *** End Patch
            """);
        assertTrue(result.text().contains("出现多次"), result.text());
        assertEquals("a\n", Files.readString(workspace.resolve("a.txt")));
        assertEquals("c\n", Files.readString(workspace.resolve("c.txt")));
        assertFalse(Files.exists(workspace.resolve("b.txt")));
        assertTrue(snapshots.isEmpty());
    }

    @Test
    void rejectsAddAndMoveTargetCollisionInEitherOrder() throws IOException {
        write("a.txt", "a\n");
        String move = "*** Update File: a.txt\n*** Move to: b.txt\n-a\n+A\n";
        String add = "*** Add File: b.txt\n+B\n";
        for (String operations : List.of(move + add, add + move)) {
            ToolResult result = apply("*** Begin Patch\n" + operations + "*** End Patch");
            assertTrue(result.text().contains("出现多次"), result.text());
            assertEquals("a\n", Files.readString(workspace.resolve("a.txt")));
            assertFalse(Files.exists(workspace.resolve("b.txt")));
        }
    }

    @Test
    void moveDoesNotOverwriteTargetCreatedAfterPlanning() throws IOException {
        write("a.txt", "a\n");
        tool = new ApplyPatchTool(new PathValidator(workspace), file -> {
            if (file.getFileName().toString().equals("b.txt")) {
                try {
                    Files.writeString(file, "concurrent content");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }, ReadGuard.allowAll());
        ToolResult result = apply("*** Begin Patch\n*** Update File: a.txt\n*** Move to: b.txt\n-a\n+A\n*** End Patch");
        assertTrue(result.text().startsWith("ERROR"), result.text());
        assertEquals("concurrent content", Files.readString(workspace.resolve("b.txt")));
        assertEquals("a\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void updatesAddsAndDeletesAcrossFilesInOnePatch() throws IOException {
        write("src/A.java", "class A {\n  int x = 1;\n  int y = 2;\n}\n");
        write("old/Legacy.java", "legacy\n");

        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: src/A.java
            @@ class A
               int x = 1;
            -  int y = 2;
            +  int y = 3;
            +  int z = 4;
            *** Add File: src/B.java
            +class B {}
            *** Delete File: old/Legacy.java
            *** End Patch
            """);

        assertTrue(result.text().startsWith("已应用补丁（3 个文件）"), result.text());
        assertEquals("class A {\n  int x = 1;\n  int y = 3;\n  int z = 4;\n}\n",
            Files.readString(workspace.resolve("src/A.java")));
        assertEquals("class B {}\n", Files.readString(workspace.resolve("src/B.java")));
        assertFalse(Files.exists(workspace.resolve("old/Legacy.java")));
        assertEquals(List.of("src/A.java", "src/B.java", "old/Legacy.java"), result.changedPaths());
        assertTrue(snapshots.containsAll(List.of("src/A.java", "src/B.java", "old/Legacy.java")), snapshots.toString());
    }

    @Test
    void failedHunkLeavesEveryFileUntouched() throws IOException {
        write("a.txt", "one\ntwo\n");
        write("b.txt", "alpha\nbeta\n");

        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: a.txt
            -one
            +ONE
            *** Update File: b.txt
            -does not exist
            +whatever
            *** End Patch
            """);

        assertTrue(result.text().startsWith("ERROR: "), result.text());
        assertTrue(result.text().contains("补丁未应用"), result.text());
        assertEquals("one\ntwo\n", Files.readString(workspace.resolve("a.txt")));
        assertTrue(result.changedPaths().isEmpty());
        assertTrue(snapshots.isEmpty());
    }

    @Test
    void ambiguousHunkIsRejectedInsteadOfPickingTheFirstMatch() throws IOException {
        write("a.txt", "x = 1\nfoo\nx = 1\nbar\n");

        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: a.txt
            -x = 1
            +x = 2
            *** End Patch
            """);

        assertTrue(result.text().contains("匹配到多处"), result.text());
        assertTrue(result.text().contains("1、3"), "多处命中应明确列出全部命中行号：" + result.text());
        assertEquals("x = 1\nfoo\nx = 1\nbar\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void matchesPublicFinalClassHeaderAndUnifiedDiffLineHeader() throws IOException {
        write("src/Demo.java", """
            public final class Demo implements Runnable {
                void first() {
                    int x = 1;
                }
                void second() {
                    int x = 1;
                }
            }
            """);

        ToolResult byClassHeader = apply("""
            *** Begin Patch
            *** Update File: src/Demo.java
            @@ class Demo
            @@ void second()
            -        int x = 1;
            +        int x = 2;
            *** End Patch
            """);
        assertTrue(byClassHeader.text().startsWith("已应用补丁"), byClassHeader.text());
        assertTrue(byClassHeader.text().contains("L6"), "返回摘要应包含变更行段：" + byClassHeader.text());
        assertTrue(Files.readString(workspace.resolve("src/Demo.java")).contains("int x = 2;"));

        ToolResult byUnifiedHeader = apply("""
            *** Begin Patch
            *** Update File: src/Demo.java
            @@ -2,3 +2,3 @@ void first()
            -        int x = 1;
            +        int x = 9;
            *** End Patch
            """);
        assertTrue(byUnifiedHeader.text().startsWith("已应用补丁"), byUnifiedHeader.text());
        assertTrue(Files.readString(workspace.resolve("src/Demo.java")).contains("int x = 9;"));
    }

    @Test
    void headerNarrowsAnOtherwiseAmbiguousHunk() throws IOException {
        write("a.txt", "void one() {\n  x = 1\n}\nvoid two() {\n  x = 1\n}\n");

        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: a.txt
            @@ void two()
            -  x = 1
            +  x = 2
            *** End Patch
            """);

        assertTrue(result.text().startsWith("已应用补丁"), result.text());
        assertEquals("void one() {\n  x = 1\n}\nvoid two() {\n  x = 2\n}\n",
            Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void requiresTheFileToHaveBeenReadFirst() throws IOException {
        Files.writeString(workspace.resolve("unread.txt"), "a\n");

        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: unread.txt
            -a
            +b
            *** End Patch
            """);

        assertTrue(result.text().contains("尚未读取过"), result.text());
        assertEquals("a\n", Files.readString(workspace.resolve("unread.txt")));
    }

    @Test
    void renamesAndEditsInOneStep() throws IOException {
        write("a/Old.java", "class Old {}\n");

        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: a/Old.java
            *** Move to: b/New.java
            @@
            -class Old {}
            +class New {}
            *** End Patch
            """);

        assertTrue(result.text().contains("重命名 a/Old.java → b/New.java"), result.text());
        assertFalse(Files.exists(workspace.resolve("a/Old.java")));
        assertEquals("class New {}\n", Files.readString(workspace.resolve("b/New.java")));
        assertEquals(List.of("a/Old.java", "b/New.java"), result.changedPaths());
    }

    @Test
    void preservesCrLfLineEndingsAndMissingFinalNewline() throws IOException {
        write("win.txt", "a\r\nb\r\nc");

        apply("""
            *** Begin Patch
            *** Update File: win.txt
             a
            -b
            +B
             c
            *** End Patch
            """);

        assertEquals("a\r\nB\r\nc", Files.readString(workspace.resolve("win.txt")));
    }

    @Test
    void toleratesTrailingWhitespaceDifferencesInContext() throws IOException {
        write("a.txt", "keep   \nold\n");

        ToolResult result = apply("""
            *** Begin Patch
            *** Update File: a.txt
             keep
            -old
            +new
            *** End Patch
            """);

        assertTrue(result.text().startsWith("已应用补丁"), result.text());
        assertEquals("keep   \nnew\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void rejectsEscapesExistingTargetsAndMalformedPatches() throws IOException {
        write("exists.txt", "x\n");

        assertTrue(apply("*** Begin Patch\n*** Add File: ../outside.txt\n+x\n*** End Patch").text().contains("超出工作区"));
        assertTrue(apply("*** Begin Patch\n*** Add File: exists.txt\n+x\n*** End Patch").text().contains("文件已存在"));
        assertTrue(apply("not a patch").text().startsWith("ERROR: 补丁格式错误"));
        assertTrue(apply("*** Begin Patch\n*** Frobnicate: x\n*** End Patch").text().contains("无法识别"));
        assertTrue(apply(
            "*** Begin Patch\n*** Update File: exists.txt\n-x\n+y\n*** Update File: exists.txt\n-x\n+z\n*** End Patch")
            .text().contains("出现多次"));
    }

    @Test
    void addedCodeWritesAtomicallyWithoutBuiltinMagicHint() throws IOException {
        ToolResult result = apply("""
            *** Begin Patch
            *** Add File: src/main/Order.java
            +class Order {
            +    int timeout = 86400;
            +}
            *** End Patch
            """);

        assertTrue(result.text().contains("已应用补丁"), result.text());
        assertFalse(result.text().contains("[代码质量提醒]"), "内置魔法值扫描已迁至 LintGate，写工具本身不附带伪质量提醒: " + result.text());
        assertEquals(List.of("src/main/Order.java"), result.changedPaths());
        assertTrue(Files.readString(workspace.resolve("src/main/Order.java")).contains("86400"));
    }

    @Test
    void isNotReadOnlyAndExposesNoSinglePath() {
        assertFalse(tool.readOnly());
        assertEquals(null, tool.changedPath(new ToolCall("1", "apply_patch", Map.of())));
    }

    @Test
    void notifiesAfterWriteAndNoteWriteForDeleteAndRenameSource() throws IOException {
        write("gone.txt", "bye\n");
        write("old.txt", "v1\n");
        List<String> afterWrites = new ArrayList<>();
        int[] writeEvents = {0};
        ReadGuard trackingGuard = new ReadGuard() {
            @Override
            public void observed(Path file) {
                observed.add(file.toAbsolutePath().normalize());
            }

            @Override
            public boolean isObserved(Path file) {
                return observed.contains(file.toAbsolutePath().normalize());
            }

            @Override
            public void noteWrite() {
                writeEvents[0]++;
            }
        };
        SnapshotSink trackingSink = new SnapshotSink() {
            @Override
            public void beforeWrite(Path file) {
                snapshots
                    .add(realWorkspace.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'));
            }

            @Override
            public void afterWrite(Path file) {
                afterWrites
                    .add(realWorkspace.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'));
            }
        };
        tool = new ApplyPatchTool(new PathValidator(workspace), trackingSink, trackingGuard);
        ToolResult result = apply("""
            *** Begin Patch
            *** Delete File: gone.txt
            *** Update File: old.txt
            *** Move to: moved.txt
            -v1
            +v2
            *** End Patch
            """);
        assertTrue(result.text().startsWith("已应用补丁"), result.text());
        assertTrue(afterWrites.containsAll(List.of("gone.txt", "old.txt", "moved.txt")), afterWrites.toString());
        assertEquals(2, writeEvents[0]);
    }
}
