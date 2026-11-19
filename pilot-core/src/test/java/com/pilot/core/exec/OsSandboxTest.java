package com.pilot.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OsSandboxTest {

    @TempDir
    Path workspace;

    @Test
    void seatbeltAllowsWorkspaceThenLocksGitMetadata() throws Exception {
        Path root = workspace.toRealPath();
        String profile =
            OsSandbox.seatbeltProfile(List.of(root, Path.of("/private/tmp")), OsSandbox.protectedWrites(root), true);

        // SBPL 字符串里反斜杠要转义，Windows 路径带反斜杠，比较时按同样的转义规则拼期望值。
        int workspaceAllow = profile.indexOf("(allow file-write* (subpath \"" + escaped(root) + "\"))");
        int gitDeny = profile.indexOf("(deny file-write* (subpath \"" + escaped(root.resolve(".git/config")) + "\"))");
        assertTrue(workspaceAllow >= 0, profile);
        assertTrue(gitDeny > workspaceAllow, "保护规则必须写在工作区允许之后");
        assertTrue(profile.contains("(allow network*)"));
        assertTrue(profile.contains("(allow file-map-executable)"));
        assertTrue(profile.contains("(deny default)"));
        assertTrue(profile.contains("(allow file-write* (literal \"/dev/null\"))"));
    }

    @Test
    void seatbeltCanDenyNetwork() {
        String profile = OsSandbox.seatbeltProfile(List.of(workspace), List.of(), false);

        assertTrue(profile.contains("(deny network*)"));
        assertFalse(profile.contains("(allow network*)"));
    }

    @Test
    void bubblewrapStartsReadOnlyAndReopensWritableRoots() throws Exception {
        Path root = Files.createDirectories(workspace.resolve("repo")).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "seed\n");
        Path bwrap = Path.of("/usr/bin/bwrap");

        List<String> argv =
            OsSandbox.bwrapCommand(bwrap, List.of(root), OsSandbox.protectedWrites(root), false, List.of("mvn", "-v"));

        assertEquals(bwrap.toString(), argv.get(0));
        assertTrue(argv.contains("--die-with-parent"));
        assertTrue(argv.contains("--unshare-net"));
        assertTrue(argv.contains("--ro-bind"));
        assertTrue(argv.contains("--bind"));
        int bind = argv.indexOf("--bind");
        assertEquals(root.toString(), argv.get(bind + 1));
        int locked = argv.lastIndexOf("--ro-bind");
        assertEquals(root.resolve(".git/config").toString(), argv.get(locked + 1));
        assertEquals(List.of("mvn", "-v"), argv.subList(argv.indexOf("--") + 1, argv.size()));
    }

    @Test
    void windowsRefusesWhenTheRunnerIsMissing() {
        String previous = System.getProperty("pilot.windows.sandbox.runner");
        System.clearProperty("pilot.windows.sandbox.runner");
        try {
            OsSandbox.Launch launch = OsSandbox.wrap(List.of("mvn", "-v"), workspace, OsSandbox.Family.WINDOWS, true);

            assertTrue(launch.command().isEmpty(), launch.label());
            assertFalse(launch.enforced());
            assertTrue(launch.label().contains("命令未启动"), launch.label());
        } finally {
            restoreRunner(previous);
        }
    }

    @Test
    void windowsFailingRunnerDoesNotStartTheCommand() throws Exception {
        Path runner = Files.writeString(workspace.resolve("fail-runner.sh"), "#!/bin/sh\nexit 7\n");
        assertTrue(runner.toFile().setExecutable(true));
        String previous = System.setProperty("pilot.windows.sandbox.runner", runner.toString());
        try {
            OsSandbox.Launch launch = OsSandbox.wrap(List.of("mvn", "-v"), workspace, OsSandbox.Family.WINDOWS, true);

            assertTrue(launch.command().isEmpty(), launch.label());
            assertFalse(launch.enforced());
            assertTrue(launch.label().contains("命令未启动"), launch.label());
            assertTrue(launch.label().contains("7"), launch.label());
        } finally {
            restoreRunner(previous);
        }
    }

    @Test
    void windowsReadyRunnerOnlyStartsBehindTheRestrictedToken() throws Exception {
        Path runner = Files.writeString(workspace.resolve("ok-runner.sh"), "#!/bin/sh\nexit 0\n");
        assertTrue(runner.toFile().setExecutable(true));
        String previous = System.setProperty("pilot.windows.sandbox.runner", runner.toString());
        try {
            OsSandbox.Launch launch = OsSandbox.wrap(List.of("mvn", "-v"), workspace, OsSandbox.Family.WINDOWS, true);

            assertTrue(launch.enforced(), launch.label());
            assertEquals(runner.toString(), launch.command().getFirst());
            assertTrue(launch.command().contains("--workspace"), launch.command().toString());
            assertTrue(launch.command().containsAll(List.of("mvn", "-v")), launch.command().toString());
            assertTrue(launch.label().contains("写入已限制"), launch.label());
        } finally {
            restoreRunner(previous);
        }
    }

    private static void restoreRunner(String previous) {
        if (previous == null) {
            System.clearProperty("pilot.windows.sandbox.runner");
        } else {
            System.setProperty("pilot.windows.sandbox.runner", previous);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macSandboxWritesInsideWorkspaceAndRejectsTheOutside() throws Exception {
        Path root = workspace.toRealPath();
        Path escape = Path.of(System.getProperty("user.home"), "pilot-os-sandbox-escape.txt");
        Files.deleteIfExists(escape);
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "seed\n");
        OsSandbox.Launch launch = OsSandbox.wrap(
            List.of("/bin/sh", "-c", "echo ok > inside.txt; echo hacked > .git/config; echo no > '" + escape + "'"),
            root);
        assertTrue(launch.enforced(), launch.label());
        assertTrue(launch.label().contains("已隔离"), launch.label());

        Process process =
            new ProcessBuilder(launch.command()).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();

        assertEquals("ok\n", Files.readString(root.resolve("inside.txt")));
        assertEquals("seed\n", Files.readString(root.resolve(".git/config")), output);
        assertFalse(Files.exists(escape), output);
        assertTrue(exit != 0, output);
        Files.deleteIfExists(escape);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macSandboxLetsGitReadDevNull() throws Exception {
        Path root = workspace.toRealPath();
        git(root, "init");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "test");
        Files.writeString(root.resolve("A.txt"), "one\n");
        git(root, "add", "A.txt");
        git(root, "commit", "-m", "init");

        OsSandbox.Launch launch = OsSandbox.wrap(List.of("git", "status", "--porcelain"), root);
        Process process =
            new ProcessBuilder(launch.command()).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();

        assertEquals(0, exit, output);
        assertFalse(output.contains("Operation not permitted"), output);
    }

    private static String escaped(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
