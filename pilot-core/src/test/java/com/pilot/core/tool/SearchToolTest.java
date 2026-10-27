package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchToolTest {
    @TempDir
    Path workspace;

    private SearchTool tool;

    @BeforeEach
    void setUp() throws IOException {
        tool = new SearchTool(new PathValidator(workspace));
        Files.writeString(workspace.resolve("a.txt"), "alpha\nbeta alpha\n");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {\n  String name = \"alpha\";\n}\n");
        Files.createDirectories(workspace.resolve("target"));
        Files.writeString(workspace.resolve("target/Generated.java"), "alpha\n");
    }

    @Test
    void returnsMatchesWithPathAndLineNumber() {
        String result = tool.execute(Map.of("pattern", "alpha"));

        assertTrue(result.contains("a.txt:1: alpha"), result);
        assertTrue(result.contains("a.txt:2: beta alpha"), result);
        assertTrue(result.contains("src/App.java:2:"), result);
    }

    @Test
    void skipsBuildDirectories() {
        String result = tool.execute(Map.of("pattern", "alpha"));

        assertFalse(result.contains("target/"), result);
    }

    @Test
    void scopesSearchToPath() {
        String result = tool.execute(Map.of("pattern", "alpha", "path", "src"));

        assertTrue(result.contains("src/App.java:2:"), result);
        assertFalse(result.contains("a.txt"), result);
    }

    @Test
    void globRestrictsSearchToMatchingFiles() throws IOException {
        Files.writeString(workspace.resolve("src/Other.txt"), "alpha in text file\\n");

        String result = tool.execute(Map.of("pattern", "alpha", "glob", "*.java"));

        assertTrue(result.contains("src/App.java:2:"), result);
        assertFalse(result.contains("a.txt"), result);
        assertFalse(result.contains("Other.txt"), result);
    }

    @Test
    void searchesLargeFilesWithRipgrepAndSkipsThemInTheFallback() throws IOException {
        var body = new StringBuilder();
        body.append("x".repeat(1024 * 1024 + 5)).append('\n').append("large-marker\n");
        Files.writeString(workspace.resolve("large.txt"), body.toString());

        String result = tool.execute(Map.of("pattern", "large-marker"));

        if (ripgrepAvailable()) {
            assertTrue(result.contains("large.txt:2: large-marker"), result);
        } else {
            assertTrue(result.startsWith("未找到匹配项"), result);
        }
    }

    @Test
    void patternStartingWithDashIsStillAPattern() throws IOException {
        Files.writeString(workspace.resolve("flags.txt"), "keep\n-flag\n");

        String result = tool.execute(Map.of("pattern", "-flag"));

        assertTrue(result.contains("flags.txt:2: -flag"), result);
    }

    private static boolean ripgrepAvailable() {
        try {
            Process process = new ProcessBuilder("rg", "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Test
    void reportsNoMatch() {
        String result = tool.execute(Map.of("pattern", "not-there-anywhere"));

        assertTrue(result.startsWith("未找到匹配项"), result);
    }

    @Test
    void rejectsInvalidPattern() {
        String result = tool.execute(Map.of("pattern", "[unclosed"));

        assertTrue(result.startsWith("ERROR: 正则表达式非法"), result);
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        String result = tool.execute(Map.of("pattern", "alpha", "path", "../.."));

        assertTrue(result.startsWith("ERROR: 路径超出工作区"), result);
    }
}
