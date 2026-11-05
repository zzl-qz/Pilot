package com.pilot.server.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopShellInstallerTest {
    @Test
    void invokesNpmThroughNodeWithoutShellExpansion(@TempDir Path root) throws Exception {
        Path npm = fakeNpm(root.resolve("中文 path & 100%"));
        Path node = npm.getParent().resolve(nodeName());
        Files.createFile(node);
        Path cli = npm.getParent().resolve("node_modules/npm/bin/npm-cli.js");
        Files.createDirectories(cli.getParent());
        Files.createFile(cli);
        assertEquals(List.of(node.toString(), cli.toString(), "install", "--no-audit", "--no-fund"),
            DesktopShellInstaller.installCommand(npm, List.of()));
        assertEquals("system-bin", DesktopShellInstaller.inheritedPath(Map.of("Path", "system-bin")));
    }

    @Test
    void installedShellIsUsedWithoutInstalling(@TempDir Path root) throws Exception {
        electron(root);
        AtomicBoolean ran = new AtomicBoolean();
        DesktopShellInstaller installer = installer(true, null, (dir, command, env, log, timeout) -> {
            ran.set(true);
            return 0;
        });

        assertNull(installer.ensureInstalled(root, () -> {
        }));
        assertFalse(ran.get());
    }

    @Test
    void backgroundInstallOpensTheWindowWhenItFinishes(@TempDir Path root) throws Exception {
        desktopPackage(root);
        CountDownLatch opened = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        DesktopShellInstaller installer = installer(true, fakeNpm(root), (dir, command, env, log, timeout) -> {
            attempts.incrementAndGet();
            assertEquals(root.resolve("pilot-desktop"), dir);
            assertTrue(String.join(" ", command).contains("install --no-audit --no-fund"),
                "安装命令应执行 npm install：" + command);
            electron(root);
            return 0;
        });

        String pending = installer.ensureInstalled(root, opened::countDown);
        assertTrue(pending.contains("正在后台安装"), pending);
        assertTrue(opened.await(10, TimeUnit.SECONDS), "安装成功后应回调打开窗口");
        assertEquals(1, attempts.get());
    }

    @Test
    void failedAttemptsFallBackToTheMirrorAndThenGiveUp(@TempDir Path root) throws Exception {
        desktopPackage(root);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch opened = new CountDownLatch(1);
        DesktopShellInstaller installer = installer(true, fakeNpm(root), (dir, command, env, log, timeout) -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                assertFalse(command.toString().contains("npmmirror"));
                assertFalse(env.containsKey("ELECTRON_MIRROR"));
            } else {
                assertTrue(command.toString().contains("npmmirror"));
                assertEquals(DesktopShellInstaller.ELECTRON_MIRROR, env.get("ELECTRON_MIRROR"));
            }
            return 1;
        });

        assertNotNull(installer.ensureInstalled(root, opened::countDown));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (installer.installing() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, attempts.get());
        assertFalse(installer.installing(), "结束后要复位，允许下次重试");
        assertEquals(1, opened.getCount(), "两次都失败就不该开窗");
    }

    @Test
    void provisionDisabledKeepsTheManualCommand(@TempDir Path root) throws Exception {
        desktopPackage(root);
        AtomicBoolean ran = new AtomicBoolean();
        DesktopShellInstaller installer = installer(false, fakeNpm(root), (dir, command, env, log, timeout) -> {
            ran.set(true);
            return 0;
        });

        String pending = installer.ensureInstalled(root, () -> {
        });
        assertTrue(pending.contains(DesktopShellInstaller.MANUAL), pending);
        assertTrue(pending.contains("provision=false"), pending);
        assertFalse(ran.get());
    }

    @Test
    void missingNpmKeepsTheManualCommand(@TempDir Path root) throws Exception {
        desktopPackage(root);
        DesktopShellInstaller installer = installer(true, null, (dir, command, env, log, timeout) -> 0);

        assertNull(DesktopShellInstaller.npm(root, "", ""));
        String pending = installer.ensureInstalled(root, () -> {
        });
        assertTrue(pending.contains(DesktopShellInstaller.MANUAL), pending);
        assertFalse(installer.installing());
    }

    @Test
    void secondCallWhileInstallingReportsProgress(@TempDir Path root) throws Exception {
        desktopPackage(root);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch opened = new CountDownLatch(1);
        DesktopShellInstaller installer = installer(true, fakeNpm(root), (dir, command, env, log, timeout) -> {
            electron(root);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });

        String first = installer.ensureInstalled(root, opened::countDown);
        assertTrue(first.contains("正在后台安装"), first);
        assertTrue(installer.ensureInstalled(root, opened::countDown).contains("正在安装"));
        release.countDown();
        assertTrue(opened.await(10, TimeUnit.SECONDS));
    }

    @Test
    void binaryIsDownloadedDirectlyWhenNpmStopsRetrying(@TempDir Path root) throws Exception {
        desktopPackage(root);
        Path npm = fakeNpm(root);
        Files.createFile(npm.getParent().resolve(nodeName()));
        Path script = root.resolve("pilot-desktop").resolve("node_modules").resolve("electron").resolve("install.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "// electron 自带的下载脚本\n");
        CountDownLatch opened = new CountDownLatch(1);
        AtomicInteger downloads = new AtomicInteger();
        DesktopShellInstaller installer = installer(true, npm, (dir, command, env, log, timeout) -> {
            if (command.size() == 2 && command.get(1).endsWith("install.js")) {
                downloads.incrementAndGet();
                assertEquals(DesktopShellInstaller.ELECTRON_MIRROR, env.get("ELECTRON_MIRROR"));
                assertEquals(npm.getParent().resolve(nodeName()).toString(), command.get(0));
                electron(root);
                return 0;
            }
            return 1; // npm 两次都失败（例如二进制下载被墙），但包已经落地
        });

        assertNotNull(installer.ensureInstalled(root, opened::countDown));
        assertTrue(opened.await(10, TimeUnit.SECONDS), "补下载成功后应回调打开窗口");
        assertEquals(1, downloads.get());
    }

    @Test
    void installCommandUsesCmdOnWindowsAndNpmElsewhere() {
        Path npm = Path.of("tools", "npm");
        List<String> command = DesktopShellInstaller.installCommand(npm, List.of());
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertEquals(List.of("cmd.exe", "/d", "/c", "\"" + npm + "\" install --no-audit --no-fund"), command);
        } else {
            assertEquals(List.of(npm.toString(), "install", "--no-audit", "--no-fund"), command);
        }
    }

    @Test
    void npmFallsBackToTheBundledRuntime(@TempDir Path root) throws Exception {
        Path bundled = bundledNpm(root);
        assertEquals(bundled, DesktopShellInstaller.npm(root, "", ""));
        // 内置运行时的位置也可以由 BundledRuntime 的系统属性指定
        assertEquals(bundled, DesktopShellInstaller.npm(root, bundled.getParent().toString(), ""));
    }

    /** 固定住 npm 的位置：不依赖测试机器上装没装 Node。 */
    private static DesktopShellInstaller installer(boolean provision, Path npm, DesktopShellInstaller.Runner runner) {
        return new DesktopShellInstaller(provision, runner) {
            @Override
            Path npm(Path root) {
                return npm;
            }
        };
    }

    private static String nodeName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "node.exe" : "node";
    }

    private static Path fakeNpm(Path root) throws IOException {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "npm.cmd" : "npm";
        Path npm = root.resolve("tools").resolve(name);
        Files.createDirectories(npm.getParent());
        Files.createFile(npm);
        npm.toFile().setExecutable(true);
        return npm;
    }

    private static Path bundledNpm(Path root) throws IOException {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "npm.cmd" : "npm";
        Path npm = root.resolve(".pilot").resolve("runtime").resolve("node").resolve(name);
        Files.createDirectories(npm.getParent());
        Files.createFile(npm);
        npm.toFile().setExecutable(true);
        return npm;
    }

    private static void desktopPackage(Path root) throws IOException {
        Files.createDirectories(root.resolve("pilot-desktop"));
        Files.writeString(root.resolve("pilot-desktop").resolve("package.json"), "{}");
    }

    private static Path electron(Path root) {
        try {
            Path binary = DesktopWindowLauncher.electronBinary(root);
            Files.createDirectories(binary.getParent());
            if (!Files.exists(binary)) {
                Files.createFile(binary);
            }
            binary.toFile().setExecutable(true);
            return binary;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
