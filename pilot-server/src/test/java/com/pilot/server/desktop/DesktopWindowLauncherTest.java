package com.pilot.server.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.pilot.server.browser.BrowserHome;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.desktop.DesktopWindowLauncher.LaunchResult;
import com.pilot.server.runtime.RuntimeMirrors;

class DesktopWindowLauncherTest {

    private static final String WINDOWS_SHELL = "C:\\repo\\pilot-desktop\\node_modules\\electron\\dist\\electron.exe";
    private static final String MAC_SHELL = "/Applications/Pilot.app/Contents/MacOS/Pilot";
    private static final String LINUX_SHELL = "/repo/pilot-desktop/node_modules/electron/dist/electron";

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
        assertThrows(IOException.class, () -> DesktopWindowLauncher.prepareDesktopBinary(root, command, log));
        assertTrue(Files.readString(log).contains("故意失败"));
    }

    @Test
    void deadPidDoesNotCountAsAnOpenWindow(@TempDir Path root) throws Exception {
        Path file = endpoint(root, endpointJson(99999999L, currentStartMillis()));
        assertFalse(DesktopWindowLauncher.alreadyOpen(endpointFile(root), pid -> WINDOWS_SHELL));
        assertFalse(Files.exists(file), "失效端点要被清理，避免每次启动都误判");
    }

    @Test
    void liveDesktopShellEndpointCountsAsAnOpenWindow(@TempDir Path root) throws Exception {
        long pid = ProcessHandle.current().pid();
        endpoint(root, endpointJson(pid, currentStartMillis()));
        assertTrue(DesktopWindowLauncher.alreadyOpen(endpointFile(root), ignored -> WINDOWS_SHELL));
    }

    @Test
    void reusedPidDoesNotCountAsAnOpenWindow(@TempDir Path root) throws Exception {
        long pid = ProcessHandle.current().pid();
        Path file = endpoint(root, endpointJson(pid, currentStartMillis()));
        assertFalse(
            DesktopWindowLauncher.alreadyOpen(endpointFile(root), ignored -> "C:\\Windows\\System32\\conhost.exe"));
        assertFalse(Files.exists(file), "PID 被系统复用时残留端点必须失效");
    }

    @Test
    void staleStartMillisDoesNotCountAsAnOpenWindow(@TempDir Path root) throws Exception {
        assumeTrue(ProcessHandle.current().info().startInstant().isPresent());
        long pid = ProcessHandle.current().pid();
        Path file = endpoint(root, endpointJson(pid, 1L));
        assertFalse(DesktopWindowLauncher.alreadyOpen(endpointFile(root), ignored -> LINUX_SHELL));
        assertFalse(Files.exists(file));
    }

    @Test
    void incompleteEndpointIsDiscarded(@TempDir Path root) throws Exception {
        long pid = ProcessHandle.current().pid();
        Path file = endpoint(root, "{\"pid\": " + pid + "}");
        assertFalse(DesktopWindowLauncher.alreadyOpen(endpointFile(root), ignored -> MAC_SHELL));
        assertFalse(Files.exists(file));
    }

    @Test
    void desktopShellCommandIsRecognizedOnAllPlatforms() {
        assertTrue(DesktopWindowLauncher.isDesktopShell(WINDOWS_SHELL));
        assertTrue(DesktopWindowLauncher.isDesktopShell(MAC_SHELL));
        assertTrue(DesktopWindowLauncher.isDesktopShell(LINUX_SHELL));
        assertTrue(DesktopWindowLauncher.isDesktopShell("C:\\Program Files\\Pilot\\pilot.exe"));
        assertFalse(DesktopWindowLauncher.isDesktopShell("C:\\Windows\\System32\\conhost.exe"));
        assertFalse(DesktopWindowLauncher.isDesktopShell("/usr/bin/java"));
    }

    @Test
    void noDesktopSessionFallsBackToWebWorkbench() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("local.server.port", "10001")));
        DesktopWindowLauncher launcher = new DesktopWindowLauncher(environment,
            new DesktopShellInstaller(false, (dir, cmd, env, log, timeout) -> 1, RuntimeMirrors.defaults()),
            browserHome(null));
        LaunchResult result = launcher.launch(() -> false);
        assertEquals("unavailable", result.status());
        assertTrue(result.message().contains("http://127.0.0.1:10001"));
        assertTrue(result.message().contains("Web 工作台"));
        assertFalse(result.message().contains("不是从源码启动"), "官方脚本 / fat jar 不应再被路径嗅探拦截");
    }

    private static long currentStartMillis() {
        return ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    private static String endpointJson(long pid, long startMillis) {
        return "{\"pid\": " + pid + ", \"startMillis\": " + startMillis
            + ", \"cdpPort\": 42756, \"controlPort\": 63187, \"token\": \"secret-token\"}";
    }

    private static Path endpoint(Path root, String json) throws IOException {
        Path file = endpointFile(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    private static Path endpointFile(Path root) {
        return browserHome(root.resolve("browser runtime").toString()).desktopEndpoint();
    }

    private static BrowserHome browserHome(String configured) {
        return new BrowserHome(new PilotDataDir(null), configured);
    }
}
