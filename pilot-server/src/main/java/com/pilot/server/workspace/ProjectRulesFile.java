package com.pilot.server.workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Shared read contract for the project editor and model prompt. */
public final class ProjectRulesFile {
    public static final int MAX_CHARS = 8_000;
    private static final String NAME = "AGENTS.md";

    private ProjectRulesFile() {
    }

    public static Path path(Path workspace) {
        return workspace.toAbsolutePath().normalize().resolve(NAME);
    }

    public static Preview read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Preview("", false);
        }
        try (
            var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS),
                StandardCharsets.UTF_8.newDecoder()))) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            char[] buffer = new char[MAX_CHARS + 1];
            int length = 0;
            while (length < buffer.length) {
                int count = reader.read(buffer, length, buffer.length - length);
                if (count < 0) {
                    break;
                }
                length += count;
            }
            int end = Math.min(length, MAX_CHARS);
            if (length > end && Character.isSurrogatePair(buffer[end - 1], buffer[end])) {
                end--;
            }
            return new Preview(new String(buffer, 0, end), length > MAX_CHARS);
        }
    }

    public record Preview(String text, boolean truncated) {
    }
}
