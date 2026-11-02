package com.pilot.server.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    @EnabledOnOs(OS.MAC)
    void preparesPilotUsingElectronNodeWithoutDependingOnIdeaNodePath(@TempDir Path temp) throws Exception {
        Path root = DesktopWindowLauncher.repositoryRoot();
        assumeTrue(root != null);
        Path electron = DesktopWindowLauncher.electronBinary(root);
        assumeTrue(Files.isExecutable(electron));
        // 独立缓存模拟首次从 IDEA 启动，确保实际使用 Electron 的 Node 完成复制和签名。
        Path fixture = Files.createDirectories(temp.resolve("pilot-desktop"));
        Path source = root.resolve("pilot-desktop");
        Files.copy(source.resolve("brand-host.mjs"), fixture.resolve("brand-host.mjs"));
        Files.createSymbolicLink(fixture.resolve("node_modules"), source.resolve("node_modules"));
        Files.createSymbolicLink(fixture.resolve("assets"), source.resolve("assets"));
        Path log = temp.resolve("prepare.log");
        Path pilot = DesktopWindowLauncher.prepareDesktopBinary(temp, electron, log);
        assertTrue(Files.isExecutable(pilot));
        assertTrue(pilot.toString().contains("Pilot.app/Contents/MacOS/Pilot"));
        assertEquals(pilot, DesktopWindowLauncher.prepareDesktopBinary(temp, electron, log));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void failedPreparationDoesNotFallBackToAnElectronWindow(@TempDir Path root) throws Exception {
        Path desktop = Files.createDirectories(root.resolve("pilot-desktop"));
        Path command = desktop.resolve("failed-prepare");
        Files.writeString(command, "#!/bin/sh\necho '故意失败' >&2\nexit 1\n");
        assertTrue(command.toFile().setExecutable(true));
        Path log = root.resolve("prepare.log");
        assertThrows(java.io.IOException.class,
                () -> DesktopWindowLauncher.prepareDesktopBinary(root, command, log));
        assertTrue(Files.readString(log).contains("故意失败"));
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
