package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.PathValidator;

class CreateFileToolTest {
    @TempDir
    Path workspace;

    @Test
    void createsNestedFileWithoutOverwritingExistingContent() throws IOException {
        CreateFileTool tool = new CreateFileTool(new PathValidator(workspace));
        assertTrue(tool.execute(Map.of("path", "nested/new.txt", "content", "first")).startsWith("已创建"));
        assertTrue(tool.execute(Map.of("path", "nested/new.txt", "content", "second")).startsWith("ERROR"));
        assertEquals("first", Files.readString(workspace.resolve("nested/new.txt")));
    }

    @Test
    void refusesDanglingSymlinkWithoutCreatingItsTarget() throws IOException {
        Path outside = workspace.getParent().resolve(workspace.getFileName() + "-outside.txt");
        try {
            Files.createSymbolicLink(workspace.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "当前账户无法创建符号链接：" + e.getMessage());
        }
        CreateFileTool tool = new CreateFileTool(new PathValidator(workspace));
        assertTrue(tool.execute(Map.of("path", "link.txt", "content", "unsafe")).startsWith("ERROR"));
        assertFalse(Files.exists(outside));
    }

    @Test
    void concurrentCreationBetweenValidationAndWriteDoesNotOverwrite() throws IOException {
        CreateFileTool tool = new CreateFileTool(new PathValidator(workspace), file -> {
            try {
                Files.writeString(file, "concurrent content");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, ReadGuard.allowAll());
        assertTrue(tool.execute(Map.of("path", "new.txt", "content", "replacement")).startsWith("ERROR"));
        assertEquals("concurrent content", Files.readString(workspace.resolve("new.txt")));
    }
}
