package com.pilot.core.tool;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.workspace.PathValidator;
import com.pilot.core.workspace.WorkspaceViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxToolsTest {

    private record Proposal(String path, String oldText, String newText) {
    }

    private static Path workspace(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.java"), "class A {\n    int x = 1;\n}\n");
        Files.writeString(dir.resolve("secret.txt"), "top secret\n");
        return dir;
    }

    @Test
    void editToolOnlyRecordsProposal(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        var proposals = new ArrayList<Proposal>();
        SandboxSink sink = (path, oldText, newText) -> proposals.add(new Proposal(path, oldText, newText));
        var tool = new SandboxEditFileTool(new PathValidator(root), sink);

        String result = tool.execute(Map.of(
                "path", "src/A.java",
                "old_string", "int x = 1;",
                "new_string", "int x = 2;"));

        assertFalse(result.startsWith("ERROR"), result);
        assertEquals(1, proposals.size());
        assertEquals("src/A.java", proposals.get(0).path());
        assertTrue(proposals.get(0).newText().contains("int x = 2;"));
        assertTrue(Files.readString(root.resolve("src/A.java")).contains("int x = 1;"),
                "真实文件必须保持不变");
    }

    @Test
    void createToolRecordsNewFile(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        var proposals = new ArrayList<Proposal>();
        var tool = new SandboxCreateFileTool(new PathValidator(root),
                (path, oldText, newText) -> proposals.add(new Proposal(path, oldText, newText)));

        tool.execute(Map.of("path", "src/B.java", "content", "class B {}\n"));

        assertEquals(1, proposals.size());
        assertNull(proposals.get(0).oldText(), "新建文件没有旧内容");
        assertFalse(Files.exists(root.resolve("src/B.java")));
    }

    @Test
    void scopeBlocksFilesOutsideWhitelist(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        var scope = AgentScope.readOnly(List.of("src/**"), List.of());
        var validator = new PathValidator(root, scope);
        var tool = new SandboxEditFileTool(validator, SandboxSink.rejectAll());

        String result = tool.execute(Map.of(
                "path", "secret.txt",
                "old_string", "top secret",
                "new_string", "leaked"));

        assertTrue(result.startsWith("ERROR"), result);
        assertTrue(result.contains("白名单"), result);
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("secret.txt"));
        assertTrue(validator.allows(validator.resolve("src/A.java")));
    }

    @Test
    void listDirHidesFilesOutsideScope(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        var scope = AgentScope.readOnly(List.of("src/**"), List.of());
        var tool = new ListDirTool(new PathValidator(root, scope));

        String result = tool.execute(Map.of("path", "."));

        assertTrue(result.contains("src/"), result);
        assertFalse(result.contains("secret.txt"), result);
    }

    @Test
    void editWithoutUniqueMatchFails(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        Files.writeString(root.resolve("src/Dup.java"), "x\nx\n");
        var tool = new SandboxEditFileTool(new PathValidator(root), SandboxSink.rejectAll());

        String result = tool.execute(Map.of("path", "src/Dup.java", "old_string", "x", "new_string", "y"));

        assertTrue(result.contains("无法唯一定位"), result);
    }
}
