package com.pilot.server.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopWindowLauncherTest {

    @Test
    void electronBinaryPointsAtThePlatformExecutable() {
        Path binary = DesktopWindowLauncher.electronBinary(Path.of("/repo"));
        String name = binary.getFileName().toString();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            assertEquals("Electron", name);
        } else if (os.contains("win")) {
            assertEquals("electron.exe", name);
        } else {
            assertEquals("electron", name);
        }
    }

    @Test
    void deadPidDoesNotCountAsAnOpenWindow(@TempDir Path root) throws Exception {
        Path file = root.resolve(".pilot").resolve("browser").resolve("desktop-browser.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"pid\": 99999999, \"cdpPort\": 1}");
        assertFalse(DesktopWindowLauncher.alreadyOpen(root));
    }

    @Test
    void currentProcessCountsAsAnOpenWindow(@TempDir Path root) throws Exception {
        Path file = root.resolve(".pilot").resolve("browser").resolve("desktop-browser.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"pid\": " + ProcessHandle.current().pid() + "}");
        assertTrue(DesktopWindowLauncher.alreadyOpen(root));
    }
}
