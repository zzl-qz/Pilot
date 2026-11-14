package com.pilot.server.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserHomeTest {
    @TempDir
    Path root;

    @Test
    void keepsRepositoryDefaultAndUsesDataDirectoryOutsideSourceTree() throws Exception {
        Path repo = Files.createDirectory(root.resolve("repo"));
        Files.createFile(repo.resolve("pom.xml"));
        Path module = Files.createDirectory(repo.resolve("pilot-server"));
        Files.createFile(module.resolve("pom.xml"));
        Path fallback = root.resolve("data").resolve("browser");

        assertEquals(repo.resolve(".pilot").resolve("browser"), BrowserHome.resolve(module, fallback, " "));
        assertEquals(fallback, BrowserHome.resolve(root, fallback, null));
    }

    @Test
    void resolvesCustomAbsoluteAndRelativePathsWithSpacesAndUnicode() throws Exception {
        Path workingDir = Files.createDirectory(root.resolve("workspace"));
        Path custom = root.resolve("浏览器 runtime");
        Path fallback = root.resolve("fallback");

        assertEquals(custom, BrowserHome.resolve(workingDir, fallback, custom.toString()));
        assertEquals(custom, BrowserHome.resolve(workingDir, fallback, Path.of("..", "浏览器 runtime").toString()));
    }
}
