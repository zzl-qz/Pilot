package com.pilot.server.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 给技能脚本准备 Python 3.12 和 Node 22。
 * 放在仓库的 .pilot/runtime 里，run_command 会优先用这里的 python3 / node / npm。
 * 不进 git，打包 exe 时把这个目录一起拷走即可。
 */
@Component
public class BundledRuntime implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BundledRuntime.class);
    static final String BIN_PROPERTY = "pilot.runtime.bin";
    private static final String NODE_VERSION = "22.23.4";
    private static final String PYTHON_RELEASE = "20261026";
    private static final String PYTHON_VERSION = "3.12.16";

    private final boolean provision;
    private volatile boolean running;

    public BundledRuntime(@Value("${pilot.runtime.provision:true}") boolean provision) {
        this.provision = provision;
    }

    @Override
    public void start() {
        Path root = runtimeRoot();
        if (provision) {
            ensure(root.resolve("python"), pythonArchive(), "python3", "python.exe");
            ensure(root.resolve("node"), nodeArchive(), "node", "node.exe");
        }
        publish(root);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    private void ensure(Path dest, Archive archive, String unixName, String windowsName) {
        if (findBin(dest, unixName, windowsName) != null) {
            return;
        }
        log.info("第一次使用，正在下载 {} 到 {}", archive.name(), dest);
        Path parent = dest.getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            log.warn("创建运行目录失败：{}", e.toString());
            return;
        }
        Path tar = parent.resolve(dest.getFileName() + ".download");
        boolean fetched = false;
        for (String url : archive.urls()) {
            if (run(parent, Duration.ofMinutes(5), "curl", "-fL", "--retry", "2", "--max-time", "180",
                    "-o", tar.toString(), url) == 0 && Files.isRegularFile(tar)) {
                fetched = true;
                break;
            }
            log.info("下载失败，换下一个地址：{}", url);
        }
        if (!fetched) {
            log.warn("没能下载 {}。技能脚本会退回系统里的 {}。", archive.name(), unixName);
            return;
        }
        Path unpack = parent.resolve(dest.getFileName() + "-unpack");
        deleteTree(unpack);
        try {
            Files.createDirectories(unpack);
        } catch (IOException e) {
            log.warn("解压目录创建失败：{}", e.toString());
            return;
        }
        if (run(parent, Duration.ofMinutes(2), "tar", "-xf", tar.toString(), "-C", unpack.toString()) != 0) {
            log.warn("解压 {} 失败", archive.name());
            return;
        }
        Path extracted = findTree(unpack, unixName, windowsName);
        if (extracted == null) {
            log.warn("压缩包里没有 {}：{}", unixName, unpack);
            return;
        }
        deleteTree(dest);
        try {
            Files.move(extracted, dest);
        } catch (IOException e) {
            log.warn("放好 {} 失败：{}", archive.name(), e.toString());
            return;
        }
        deleteTree(unpack);
        try {
            Files.deleteIfExists(tar);
        } catch (IOException ignored) {
            // 压缩包留着不影响使用
        }
        linkPython(dest);
    }

    private void publish(Path root) {
        List<String> bins = new ArrayList<>();
        addBin(bins, root.resolve("python"), "python3", "python.exe");
        addBin(bins, root.resolve("node"), "node", "node.exe");
        if (bins.isEmpty()) {
            return;
        }
        String value = String.join(java.io.File.pathSeparator, bins);
        System.setProperty(BIN_PROPERTY, value);
        log.info("技能脚本使用内置运行环境：{}", value);
    }

    private static void addBin(List<String> bins, Path root, String unixName, String windowsName) {
        Path bin = findBin(root, unixName, windowsName);
        if (bin != null) {
            bins.add(bin.getParent().toString());
        }
    }

    private static Path findBin(Path root, String unixName, String windowsName) {
        if (root == null || Files.notExists(root)) {
            return null;
        }
        for (String name : List.of(unixName, windowsName)) {
            Path nested = root.resolve("bin").resolve(name);
            if (Files.isRegularFile(nested)) {
                return nested;
            }
            Path flat = root.resolve(name);
            if (Files.isRegularFile(flat)) {
                return flat;
            }
        }
        return null;
    }

    private static Path findTree(Path unpack, String unixName, String windowsName) {
        if (findBin(unpack, unixName, windowsName) != null) {
            return unpack;
        }
        try (var children = Files.list(unpack)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child) && findBin(child, unixName, windowsName) != null) {
                    return child;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static void linkPython(Path dest) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        Path python3 = dest.resolve("bin").resolve("python3");
        Path python = dest.resolve("bin").resolve("python");
        if (Files.isRegularFile(python3) && Files.notExists(python)) {
            try {
                Files.createSymbolicLink(python, Path.of("python3"));
            } catch (IOException ignored) {
                // 没有 python 这个名字时，python3 仍然可用
            }
        }
    }

    private static Path runtimeRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isRegularFile(dir.resolve("pilot-server").resolve("pom.xml"))) {
                return dir.resolve(".pilot").resolve("runtime");
            }
            dir = dir.getParent();
        }
        return Path.of(System.getProperty("user.dir", "."), ".pilot", "runtime");
    }

    private static Archive pythonArchive() {
        String triple = pythonTriple();
        String file = "cpython-" + PYTHON_VERSION + "+" + PYTHON_RELEASE + "-" + triple + "-install_only.tar.gz";
        String encoded = file.replace("+", "%2B");
        String origin = "https://github.com/astral-sh/python-build-standalone/releases/download/"
                + PYTHON_RELEASE + "/" + file;
        return new Archive("Python " + PYTHON_VERSION, List.of(
                "https://registry.npmmirror.com/-/binary/python-build-standalone/" + PYTHON_RELEASE + "/" + encoded,
                origin));
    }

    private static Archive nodeArchive() {
        String tag = nodeTag();
        String file = "node-v" + NODE_VERSION + "-" + tag + (tag.startsWith("win-") ? ".zip" : ".tar.gz");
        String origin = "https://nodejs.org/dist/v" + NODE_VERSION + "/" + file;
        return new Archive("Node.js " + NODE_VERSION, List.of(
                origin,
                "https://npmmirror.com/mirrors/node/v" + NODE_VERSION + "/" + file));
    }

    private static String pythonTriple() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return arm ? "aarch64-pc-windows-msvc" : "x86_64-pc-windows-msvc";
        }
        if (os.contains("mac")) {
            return arm ? "aarch64-apple-darwin" : "x86_64-apple-darwin";
        }
        return arm ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu";
    }

    private static String nodeTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return arm ? "win-arm64" : "win-x64";
        }
        if (os.contains("mac")) {
            return arm ? "darwin-arm64" : "darwin-x64";
        }
        return arm ? "linux-arm64" : "linux-x64";
    }

    private static int run(Path directory, Duration timeout, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 下次启动再清理
                }
            });
        } catch (IOException ignored) {
            // 目录正在被占用
        }
    }

    private record Archive(String name, List<String> urls) {
    }
}
