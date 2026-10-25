package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ListDirToolTest {
    @TempDir
    Path workspace;

    @Test
    void listsDirectoryWithTypeMarkers() throws IOException {
        Files.writeString(workspace.resolve("app.js"), "console.log(1)");
        Files.createDirectories(workspace.resolve("src"));
        var tool = new ListDirTool(new PathValidator(workspace));

        String result = tool.execute(Map.of());

        assertTrue(result.startsWith("目录: .（2 项）"), result);
        assertTrue(result.contains("app.js  (14 B)"), result);
        assertTrue(result.contains("src/"), result);
    }

    @Test
    void listsNestedDirectory() throws IOException {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("src/main/App.java"), "class App {}");
        var tool = new ListDirTool(new PathValidator(workspace));

        String result = tool.execute(Map.of("path", "src/main"));

        assertTrue(result.startsWith("目录: src/main（1 项）"), result);
        assertTrue(result.contains("App.java"), result);
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        var tool = new ListDirTool(new PathValidator(workspace));

        String result = tool.execute(Map.of("path", ".."));

        assertTrue(result.startsWith("ERROR: 路径超出工作区"), result);
    }

    @Test
    void reportsMissingDirectory() {
        var tool = new ListDirTool(new PathValidator(workspace));

        String result = tool.execute(Map.of("path", "nope"));

        assertTrue(result.startsWith("ERROR: 不是目录"), result);
    }
}
