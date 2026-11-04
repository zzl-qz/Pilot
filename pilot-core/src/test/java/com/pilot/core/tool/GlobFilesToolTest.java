package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.workspace.PathValidator;

class GlobFilesToolTest {
    @TempDir
    Path workspace;

    private void file(String name, long modified) throws IOException {
        Path path = workspace.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
    }

    @Test
    void findsFilesInTheWholeSubtreeNewestFirst() throws IOException {
        file("Top.java", 1_000);
        file("src/main/A.java", 3_000);
        file("src/test/B.java", 2_000);
        file("src/main/readme.md", 4_000);
        GlobFilesTool tool = new GlobFilesTool(new PathValidator(workspace));

        String result = tool.execute(Map.of("pattern", "**/*.java"));

        assertEquals("src/main/A.java\nsrc/test/B.java\nTop.java\n（共 3 个）", result);
    }

    @Test
    void skipsDependencyAndBuildDirectories() throws IOException {
        file("src/A.js", 1);
        file("node_modules/dep/index.js", 2);
        file("target/classes/B.js", 3);
        GlobFilesTool tool = new GlobFilesTool(new PathValidator(workspace));

        String result = tool.execute(Map.of("pattern", "**/*.js"));

        assertTrue(result.startsWith("src/A.js"), result);
        assertFalse(result.contains("node_modules") || result.contains("target"), result);
    }

    @Test
    void supportsBasePathAndLimit() throws IOException {
        file("a/one.txt", 1);
        file("a/two.txt", 2);
        file("b/three.txt", 3);
        GlobFilesTool tool = new GlobFilesTool(new PathValidator(workspace));

        String result = tool.execute(Map.of("pattern", "*.txt", "path", "a", "limit", 1));

        assertTrue(result.startsWith("a/two.txt"), result);
        assertTrue(result.contains("共 2 个匹配，仅显示最近修改的前 1 个"), result);
        assertFalse(result.contains("three"), result);
    }

    @Test
    void reportsNoMatchAndRejectsBadInput() {
        GlobFilesTool tool = new GlobFilesTool(new PathValidator(workspace));

        assertEquals("没有匹配的文件: **/*.rs", tool.execute(Map.of("pattern", "**/*.rs")));
        assertTrue(tool.execute(Map.of("pattern", "/etc/*")).startsWith("ERROR: pattern 必须是相对"));
        assertTrue(tool.execute(Map.of("pattern", "../*")).startsWith("ERROR: pattern 必须是相对"));
        assertTrue(tool.execute(Map.of("pattern", "*", "path", "../x")).startsWith("ERROR: "));
        assertTrue(tool.execute(Map.of()).startsWith("ERROR: 缺少参数"));
    }

    @Test
    void respectsAgentScopeVisibility() throws IOException {
        file("src/open/A.java", 1);
        file("src/secret/B.java", 2);
        AgentScope scope = AgentScope.readOnly(List.of("src/open/**"), List.of());
        GlobFilesTool tool = new GlobFilesTool(new PathValidator(workspace, scope));

        String result = tool.execute(Map.of("pattern", "**/*.java"));

        assertTrue(result.contains("src/open/A.java"), result);
        assertFalse(result.contains("secret"), result);
    }
}
