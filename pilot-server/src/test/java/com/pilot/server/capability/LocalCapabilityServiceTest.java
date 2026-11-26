package com.pilot.server.capability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCapabilityServiceTest {
    @TempDir
    Path directory;

    @Test
    void discoversExecutableWithoutLaunchingIt() throws Exception {
        Path executable = directory.resolve("pilot-probe");
        Files.writeString(executable, "this program must not be launched");
        executable.toFile().setExecutable(true);

        var capability = LocalCapabilityService.discoverForTest("probe", "Probe",
            List.of(executable.getFileName().toString()), List.of(directory), false);

        assertTrue(capability.available());
        assertTrue(capability.executable().endsWith(executable.getFileName().toString()));
        assertTrue(capability.reason().contains("未启动"));
    }

    @Test
    void missingCapabilityProvidesPreparationSuggestionWithoutClaimingInstallation() {
        var capability = LocalCapabilityService.discoverForTest("office", "Office", List.of("missing-office-binary"),
            List.of(directory), false);

        assertFalse(capability.available());
        assertNotNull(capability.preparationAdvice());
        assertTrue(capability.preparationAdvice().contains("office"));
    }

    @Test
    void findsApplicationLocationsByPlatformWithoutLaunchingOrUsingHostSpecificHome() {
        var windows = LocalCapabilityService.applicationCandidates("office", "Windows 11",
            Map.of("ProgramFiles", directory.toString()), directory);
        assertTrue(
            windows.stream().anyMatch(path -> path.startsWith(directory) && path.toString().endsWith("EXCEL.EXE")));
        var mac = LocalCapabilityService.applicationCandidates("browser", "Darwin", Map.of(), directory);
        assertTrue(mac.stream().anyMatch(path -> path.startsWith(directory.resolve("Applications"))));
        assertTrue(LocalCapabilityService.applicationCandidates("office", "Linux", Map.of(), directory).isEmpty());
    }

    @Test
    void windowsCapabilityLookupPrefersExecutableExtensionsAndSkipsStoreStubs() throws Exception {
        Path extensionless = directory.resolve("npm");
        Files.writeString(extensionless, "shell shim");
        extensionless.toFile().setExecutable(true);
        Files.writeString(directory.resolve("npm.cmd"), "@echo off");
        var npm = LocalCapabilityService.discoverForTest("npm", "npm", List.of("npm"), List.of(directory), true);
        assertTrue(npm.available());
        assertTrue(npm.executable().endsWith("npm.cmd"));

        Path store = directory.resolve("WindowsApps");
        Files.createDirectories(store);
        Files.createFile(store.resolve("python.exe"));
        var python =
            LocalCapabilityService.discoverForTest("python", "Python", List.of("python.exe"), List.of(store), true);
        assertFalse(python.available());

        Path nonExecutable = Files.writeString(directory.resolve("fake-docker"), "#!/bin/sh\n");
        nonExecutable.toFile().setExecutable(false);
        var docker = LocalCapabilityService.discoverForTest("docker", "Docker", List.of("fake-docker"),
            List.of(directory), false);
        assertFalse(docker.available());
    }
}
