package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandRuntimeEnvironmentTest {
    @TempDir
    Path workspace;

    @Test
    void filtersCommandEnvironmentAndPrioritizesBundledRuntime() throws Exception {
        Path bin = Files.createDirectory(workspace.resolve("runtime-bin"));
        Path python = Files.createFile(bin.resolve(isWindows() ? "python3.cmd" : "python3"));
        String previous = System.getProperty("pilot.runtime.bin");
        System.setProperty("pilot.runtime.bin", bin.toString());
        try {
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("PATH", workspace.toString());
            environment.put("HOME", "/Users/dev");
            environment.put("USER", "dev");
            environment.put("OPENAI_API_KEY", "sk-test");
            environment.put("GITHUB_TOKEN", "ghp_xxx");

            CommandRuntimeEnvironment.prepareEnvironment(environment);

            assertFalse(environment.containsKey("OPENAI_API_KEY"));
            assertFalse(environment.containsKey("GITHUB_TOKEN"));
            assertEquals("/Users/dev", environment.get("HOME"));
            assertEquals("dev", environment.get("USER"));
            assertTrue(environment.get("PATH").startsWith(bin.toString()), environment.get("PATH"));
            assertFalse(environment.get("JAVA_HOME").isBlank());
            assertEquals("cat", environment.get("PAGER"));
            assertEquals(python.toAbsolutePath(),
                CommandRuntimeEnvironment.resolveOnPath("python3", workspace.toString(), isWindows()).toAbsolutePath());
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.runtime.bin");
            } else {
                System.setProperty("pilot.runtime.bin", previous);
            }
        }
    }

    @Test
    void resolvesPathEntriesAndWindowsSuffixesInPriorityOrder() throws Exception {
        Path first = Files.createDirectory(workspace.resolve("中文 tools"));
        Path second = Files.createDirectory(workspace.resolve("fallback"));
        Path unix = Files.createFile(first.resolve("tool"));
        Path batch = Files.createFile(first.resolve("tool.bat"));
        Path laterExe = Files.createFile(second.resolve("tool.exe"));
        String path = String.join(File.pathSeparator, "bad\0path", " " + first + " ", second.toString());

        assertEquals(unix, CommandRuntimeEnvironment.resolveOnPath("tool", path, false));
        assertEquals(batch, CommandRuntimeEnvironment.resolveOnPath("tool", path, true));
        Path cmd = Files.createFile(first.resolve("tool.cmd"));
        assertEquals(cmd, CommandRuntimeEnvironment.resolveOnPath("tool", path, true));
        Files.delete(cmd);
        Files.delete(batch);
        assertEquals(laterExe, CommandRuntimeEnvironment.resolveOnPath("tool", path, true));
        assertNull(CommandRuntimeEnvironment.resolveOnPath("absent-command", workspace.toString(), isWindows()));
    }

    @Test
    void findsOnlyPlatformAppropriateWorkspaceWrappers() throws Exception {
        Path unix = Files.createFile(workspace.resolve("mvnw"));
        Path cmd = Files.createFile(workspace.resolve("mvnw.cmd"));
        Path bat = Files.createFile(workspace.resolve("mvnw.bat"));

        assertEquals(unix, CommandRuntimeEnvironment.findWrapper(workspace, "mvnw", false));
        assertEquals(cmd, CommandRuntimeEnvironment.findWrapper(workspace, "mvnw", true));
        Files.delete(cmd);
        assertEquals(bat, CommandRuntimeEnvironment.findWrapper(workspace, "mvnw", true));
        assertNull(CommandRuntimeEnvironment.findWrapper(workspace, "gradlew", true));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
