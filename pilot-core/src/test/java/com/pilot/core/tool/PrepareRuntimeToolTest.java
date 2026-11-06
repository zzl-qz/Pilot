package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.PathValidator;

class PrepareRuntimeToolTest {
    @TempDir
    Path root;

    @Test
    void planDoesNotStartProcessesAndInstallRequiresExplicitConfirmation() {
        var tool = new PrepareRuntimeTool(new PathValidator(root), (c, w, l) -> {
            fail("plan must not execute");
            return 1;
        }, List.of("python3"), false);
        assertTrue(tool.execute(Map.of("action", "plan", "recipe", "excel")).contains("openpyxl==3.1.5"));
        assertTrue(tool.confirm(Map.of("action", "prepare")));
        assertFalse(Files.exists(root.resolve(".pilot-task-envs")));
        assertTrue(tool.approvalDescription(Map.of("action", "prepare", "recipe", "unknown")).contains("拒绝"));
        assertTrue(tool.execute(Map.of("action", "prepare", "recipe", "unknown")).startsWith("ERROR:"));
    }

    @Test
    void usesPlatformPathsAndPinnedPackages() {
        for (boolean windows : List.of(false, true)) {
            List<List<String>> commands = new ArrayList<>();
            var tool = new PrepareRuntimeTool(new PathValidator(root), (c, w, l) -> {
                commands.add(c);
                return 0;
            }, List.of("python"), windows);
            String result = tool.execute(Map.of("action", "prepare", "recipe", "slides"));
            assertFalse(result.startsWith("ERROR"), result);
            assertEquals(3, commands.size());
            assertTrue(
                commands.get(1).get(0).replace('\\', '/').endsWith(windows ? "Scripts/python.exe" : "bin/python"));
            assertTrue(commands.get(1).contains("python-pptx==1.0.2"));
            assertTrue(commands.get(1).contains("--only-binary=:all:"));
        }
    }

    @Test
    void failedPreparationCleansEnvironmentAndKeepsEvidence() throws Exception {
        var tool = new PrepareRuntimeTool(new PathValidator(root), (c, w, l) -> {
            Path env = Path.of(c.getLast());
            Files.createDirectories(env);
            Files.writeString(env.resolve("partial"), "x");
            return 1;
        }, List.of("python3"), false);
        assertTrue(tool.execute(Map.of("action", "prepare", "recipe", "excel")).startsWith("ERROR"));
        try (var list = Files.list(root.resolve(".pilot-task-envs"))) {
            Path entry = list.findFirst().orElseThrow();
            assertFalse(Files.exists(entry.resolve("venv")));
            assertTrue(Files.readString(entry.resolve("status.txt")).startsWith("FAILED"));
        }
        assertTrue(tool.execute(Map.of("action", "cleanup", "env_id", "../../data")).startsWith("ERROR"));
    }
}
