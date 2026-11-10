package com.pilot.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;

class WorkspacePathIdentityTest {
    @TempDir
    Path root;

    @Test
    void emptyDirectoryProbeLeavesNoFilesAndMatchesTheActualFilesystem() throws Exception {
        WorkspacePathIdentity identity = new WorkspacePathIdentity(root);
        String upper = identity.key("New/Note.txt");
        String lower = identity.key("new/note.txt");
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
        Path probe = Files.writeString(root.resolve("CaseProbe"), "probe");
        boolean insensitive = Files.exists(root.resolve("caseprobe"));
        assertEquals(insensitive, upper.equals(lower));
        Files.delete(probe);
    }

    @Test
    void caseSensitiveFilesystemKeepsBothNewFilesIndependent() throws Exception {
        // ZIP 文件系统提供区分大小写的标准 Java NIO provider，在 macOS/Windows 也能验证此分支。
        try (var fs = FileSystems.newFileSystem(root.resolve("case-sensitive.zip"), Map.of("create", "true"))) {
            Path workspace = fs.getPath("/");
            WorkspacePathIdentity identity = new WorkspacePathIdentity(workspace);
            assertNotEquals(identity.key("New/Note.txt"), identity.key("new/note.txt"));
            DiffSandbox box = new DiffSandbox("box", "agent", "task", "", workspace);
            box.add("Note.txt", null, "upper", false, RiskLevel.LOW);
            box.add("note.txt", null, "lower", false, RiskLevel.LOW);
            assertEquals(2, box.fileCount());
            assertEquals("upper", box.proposedText("Note.txt"));
            assertEquals("lower", box.proposedText("note.txt"));
            try (var files = Files.list(workspace)) {
                assertEquals(0, files.count());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"Σ.txt, ς.txt", "ß.txt, SS.txt", "ẞ.txt, ß.txt", "Café.txt, Cafe\u0301.txt", "I.txt, ı.txt",
        "é.txt, e.txt", "①.txt, 1.txt"})
    void unicodeNamesFollowBothNativeAndCaseSensitiveFilesystemRules(String first, String second) throws Exception {
        assertAliases(root, first, second);
        try (var fs = FileSystems.newFileSystem(root.resolve("unicode.zip"), Map.of("create", "true"))) {
            assertAliases(fs.getPath("/"), first, second);
        }
    }

    private static void assertAliases(Path workspace, String first, String second) throws Exception {
        Path original = Files.writeString(workspace.resolve(first), "probe");
        boolean aliases = Files.exists(workspace.resolve(second));
        Files.delete(original);
        WorkspacePathIdentity identity = new WorkspacePathIdentity(workspace);
        assertEquals(aliases, identity.key(first).equals(identity.key(second)));
        DiffSandbox box = new DiffSandbox("box", "agent", "task", "", workspace);
        box.add(first, null, "first", false, RiskLevel.LOW);
        box.add(second, null, "second", false, RiskLevel.LOW);
        assertEquals(aliases ? 1 : 2, box.fileCount());
        assertEquals(aliases ? "second" : "first", box.proposedText(first));
        assertEquals("second", box.proposedText(second));
        try (var files = Files.list(workspace)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsEscapesBeforeProbingAnyDirectory() throws Exception {
        WorkspacePathIdentity identity = new WorkspacePathIdentity(root);
        assertThrows(WorkspaceViolationException.class, () -> identity.key("../outside.txt"));
        try (var files = Files.list(root)) {
            assertTrue(files.findAny().isEmpty());
        }
    }
}
