package com.pilot.core.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsSandboxTest {

    @TempDir
    Path workspace;

    @Test
    void seatbeltAllowsWorkspaceThenLocksGitMetadata() throws Exception {
        Path root = workspace.toRealPath();
        String profile = OsSandbox.seatbeltProfile(
                List.of(root, Path.of("/private/tmp")),
                OsSandbox.protectedWrites(root),
                true);

        int workspaceAllow = profile.indexOf("(allow file-write* (subpath \"" + root + "\"))");
        int gitDeny = profile.indexOf("(deny file-write* (subpath \"" + root.resolve(".git/config") + "\"))");
        assertTrue(workspaceAllow >= 0, profile);
        assertTrue(gitDeny > workspaceAllow, "保护规则必须写在工作区允许之后");
        assertTrue(profile.contains("(allow network*)"));
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

        List<String> argv = OsSandbox.bwrapCommand(bwrap, List.of(root), OsSandbox.protectedWrites(root),
                false, List.of("mvn", "-v"));

        assertEquals("/usr/bin/bwrap", argv.get(0));
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
    void windowsStaysOnTheHostAndSaysSo() {
        OsSandbox.Launch launch = OsSandbox.wrap(List.of("mvn", "-v"), workspace,
                OsSandbox.Family.WINDOWS, true);

        assertEquals(List.of("mvn", "-v"), launch.command());
        assertFalse(launch.enforced());
        assertTrue(launch.label().contains("Windows"));
        assertTrue(launch.label().contains("未启用"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macSandboxWritesInsideWorkspaceAndRejectsTheOutside() throws Exception {
        Path root = workspace.toRealPath();
        Path escape = Path.of(System.getProperty("user.home"), "pilot-os-sandbox-escape.txt");
        Files.deleteIfExists(escape);
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "seed\n");
        OsSandbox.Launch launch = OsSandbox.wrap(List.of("/bin/sh", "-c",
                "echo ok > inside.txt; echo hacked > .git/config; echo no > '" + escape + "'"), root);
        assertTrue(launch.enforced(), launch.label());

        Process process = new ProcessBuilder(launch.command()).directory(root.toFile())
                .redirectErrorStream(true).start();
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
        Process process = new ProcessBuilder(launch.command()).directory(root.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();

        assertEquals(0, exit, output);
        assertFalse(output.contains("Operation not permitted"), output);
    }

    private static void git(Path directory, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
