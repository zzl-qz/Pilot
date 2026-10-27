package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        String result = tool.execute(Map.of("path", "sample.txt", "old_string", "useful target lnie", "new_string", "replacement"));

        assertTrue(result.contains("最接近的文件片段"), result);
        assertTrue(result.contains("2:   useful target line"), result);
    }

    @Test
    void replacesUniqueTailInFileLargerThanFormerCeiling() throws IOException {
        Path file = workspace.resolve("big.txt");
        byte[] prefix = "a".repeat(512 * 1024).getBytes(StandardCharsets.UTF_8);
        Files.write(file, prefix);
        Files.writeString(file, "\nUNIQUE_TAIL\n", java.nio.file.StandardOpenOption.APPEND);

        String result = tool.execute(Map.of(
                "path", "big.txt",
                "old_string", "UNIQUE_TAIL",
                "new_string", "DONE"));

        assertTrue(result.startsWith("已修改 "), result);
        String updated = Files.readString(file);
        assertTrue(updated.endsWith("\nDONE\n"), updated.substring(updated.length() - 20));
        assertEquals(-1, updated.indexOf("UNIQUE_TAIL"));
    }
}
