package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadAttachmentToolTest {
    @TempDir
    Path root;

    @Test
    void readsTextAttachmentByAbsolutePathWithPagination() throws Exception {
        Path dir = root.resolve("ses_1");
        Files.createDirectories(dir);
        Path file = dir.resolve("12345678-1234-1234-1234-123456789abc-notes.txt");
        Files.writeString(file, "l1\nl2\nl3\nl4\n");

        ReadAttachmentTool tool = new ReadAttachmentTool(dir);

        String full = tool.execute(Map.of("path", file.toString()));
        assertTrue(full.contains("l1") && full.contains("l4"), full);

        String window = tool.execute(Map.of("path", file.toString(), "start_line", 2, "line_count", 2));
        assertTrue(window.contains("l2") && window.contains("l3"), window);
        assertFalse(window.contains("l1"), "分页窗口外不应出现");
    }

    @Test
    void rejectsPathsOutsideOwnSessionDirectory() throws Exception {
        Path dir = root.resolve("ses_1");
        Files.createDirectories(dir);
        Path other = root.resolve("ses_2");
        Files.createDirectories(other);
        Path outside = other.resolve("12345678-1234-1234-1234-123456789abc-secret.txt");
        Files.writeString(outside, "secret");

        ReadAttachmentTool tool = new ReadAttachmentTool(dir);

        String result = tool.execute(Map.of("path", outside.toString()));
        assertTrue(result.startsWith("ERROR"), result);
        assertTrue(result.contains("超出"), result);
    }
}
