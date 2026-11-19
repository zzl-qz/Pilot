package com.pilot.core.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 同目录名字只提示、只提示一次，失败和构建产物都不挡写入。 */
class SiblingHintsTest {
    @TempDir
    Path root;

    @Test
    void showsSiblingExportsOnceAndSkipsSelf() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Clock.java"), """
            public final class Clock {
                public static final int DEFAULT_TIMEOUT = 30;
                public String formatClock(int value) { return ""; }
            }
            """);
        Files.writeString(root.resolve("src/App.java"), "public final class App {}\n");

        SiblingHints hints = new SiblingHints();
        String first = hints.note(root, List.of("src/App.java"));
        assertTrue(first.contains("Clock.java: DEFAULT_TIMEOUT, formatClock"), first);
        assertFalse(first.contains("App.java:"));
        assertTrue(first.contains("量纲对不上就不要引用"));
        assertTrue(first.contains("只提示一次"));

        String again = hints.note(root, List.of("src/App.java"));
        assertEquals("", again, "同一文件本轮不能反复提示");
    }

    @Test
    void ignoresBundlesMissingDirsAndPathsOutsideWorkspace() throws Exception {
        Files.createDirectories(root.resolve("src/main/resources/static/assets"));
        Files.writeString(root.resolve("src/main/resources/static/assets/index-C56B2kUi.js"),
            "export const HUGE = 1;\n");
        SiblingHints hints = new SiblingHints();
        assertEquals("", hints.note(root, List.of("src/main/resources/static/assets/index-C56B2kUi.js")));
        assertEquals("", hints.note(root, List.of("src/missing/New.java")));
        assertEquals("", hints.note(root, List.of("../Outside.java")));
    }

    @Test
    void stopsAfterThePerRunCapSoLaterFilesStaySilent() throws Exception {
        Files.createDirectories(root.resolve("pkg"));
        Files.writeString(root.resolve("pkg/Shared.java"), "public static final int SHARED_LIMIT = 3;\n");
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < SiblingHints.MAX_FILES + 2; i++) {
            String name = "pkg/Item" + i + ".java";
            Files.writeString(root.resolve(name), "public final class Item" + i + " {}\n");
            paths.add(name);
        }
        SiblingHints hints = new SiblingHints();
        int shown = 0;
        for (String path : paths) {
            if (!hints.note(root, List.of(path)).isBlank()) {
                shown++;
            }
        }
        assertEquals(SiblingHints.MAX_FILES, shown);
        assertEquals("", hints.note(root, List.of(paths.getLast())));
    }
}
