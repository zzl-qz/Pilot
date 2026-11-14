package com.pilot.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLayoutTest {
    @TempDir
    Path directory;

    @Test
    void findsOnlyTheRequiredModulesFromAnIdeWorkingDirectory() throws Exception {
        Path root = Files.createDirectory(directory.resolve("Pilot 源码"));
        Files.createFile(root.resolve("pom.xml"));
        Path server = Files.createDirectory(root.resolve("pilot-server"));
        Files.createFile(server.resolve("pom.xml"));
        Path start = Files.createDirectories(server.resolve("target/classes"));

        assertEquals(root, SourceLayout.serverRoot(start));
        assertNull(SourceLayout.desktopRoot(start));
        assertNull(SourceLayout.frontendRoot(start));
        assertNull(SourceLayout.staticSource(start));

        Files.createDirectory(root.resolve("pilot-desktop"));
        Path frontend = Files.createDirectories(root.resolve("pilot-web/frontend"));
        Files.createFile(frontend.resolve("package.json"));
        Path staticFiles = Files.createDirectories(root.resolve("pilot-web/src/main/resources/static"));
        assertEquals(root, SourceLayout.desktopRoot(start));
        assertEquals(root, SourceLayout.frontendRoot(start));
        assertEquals(frontend, SourceLayout.frontendDirectory(root));
        assertEquals(staticFiles, SourceLayout.staticSource(start));
        assertEquals(root.resolve(".pilot/runtime"), SourceLayout.bundledRuntime(root));
    }

    @Test
    void leavesPackagedLaunchOutsideTheSourceTree() {
        assertNull(SourceLayout.serverRoot(directory));
        assertNull(SourceLayout.desktopRoot(directory));
        assertNull(SourceLayout.frontendRoot(directory));
        assertNull(SourceLayout.staticSource(directory));
    }
}
