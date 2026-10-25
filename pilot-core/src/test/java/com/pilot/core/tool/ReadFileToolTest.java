package com.pilot.core.tool;

import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {
    @TempDir
    Path workspace;

    private ReadFileTool tool;

    @BeforeEach
    void setUp() {
        tool = new ReadFileTool(new PathValidator(workspace));
    }

    @Test
    void returnsContentWithLineNumbers() throws IOException {
        Files.writeString(workspace.resolve("App.java"), "class App {\n  int x = 1;\n}\n");

        String result = tool.execute(Map.of("path", "App.java"));

        assertEquals("1 | class App {\n2 |   int x = 1;\n3 | }", result);
    }

    @Test
    void reportsMissingFile() {
        String result = tool.execute(Map.of("path", "missing.txt"));

        assertTrue(result.startsWith("ERROR: 文件不存在"), result);
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        String result = tool.execute(Map.of("path", "../../etc/passwd"));

        assertTrue(result.startsWith("ERROR: 路径超出工作区"), result);
    }

    @Test
    void rejectsDirectory() throws IOException {
        Files.createDirectories(workspace.resolve("src"));

        String result = tool.execute(Map.of("path", "src"));

        assertTrue(result.startsWith("ERROR: 不是普通文件"), result);
    }

    @Test
    void reportsBinaryFile() throws IOException {
        Files.write(workspace.resolve("logo.bin"), new byte[]{1, 0, 2, 0});

        String result = tool.execute(Map.of("path", "logo.bin"));

        assertTrue(result.startsWith("ERROR: 二进制文件"), result);
    }

    @Test
    void requiresPathArgument() {
        assertEquals("ERROR: 缺少参数 path", tool.execute(Map.of()));
    }
}
