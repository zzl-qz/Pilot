package com.pilot.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutablePathTest {
    @TempDir
    Path directory;

    @Test
    void ignoresInvalidEntriesAndPreservesDirectoryAndExtensionPriority() {
        Path first = directory.resolve("内置 工具");
        Path second = directory.resolve("system tools");
        String path = String.join(File.pathSeparator, "", first.toString(), "broken\0path", second.toString());

        var candidates = ExecutablePath.candidates(ExecutablePath.directories(path), "node.exe", "node.cmd");

        assertEquals(List.of(first.resolve("node.exe"), first.resolve("node.cmd"), second.resolve("node.exe"),
            second.resolve("node.cmd")), candidates.toList());
    }

    @Test
    void treatsUnsetAndBlankPathsAsEmpty() {
        assertTrue(ExecutablePath.directories(null).findAny().isEmpty());
        assertTrue(ExecutablePath.directories("  ").findAny().isEmpty());
    }

    @Test
    void preservesTheExistingSpellingOfTheEnvironmentKey() {
        Map<String, String> env = Map.of("Path", "system-bin");
        assertEquals("Path", ExecutablePath.key(env));
        assertEquals("system-bin", ExecutablePath.inherited(env));
        assertEquals("PATH", ExecutablePath.key(Map.of()));
        assertEquals("", ExecutablePath.inherited(Map.of()));
    }
}
