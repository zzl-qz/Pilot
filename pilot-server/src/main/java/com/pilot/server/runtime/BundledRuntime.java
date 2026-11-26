package com.pilot.server.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.pilot.core.exec.ProcessSupport;
import com.pilot.core.runtime.bootstrap.BootstrapPlatform;
import com.pilot.core.runtime.bootstrap.RuntimeAssets;
import com.pilot.core.runtime.bootstrap.RuntimeEnvironment;
import com.pilot.core.runtime.bootstrap.SourceEnvironment;
import com.pilot.core.util.FileTrees;
import com.pilot.core.util.HostOs;
import com.pilot.server.store.PilotDataDir;

import lombok.extern.slf4j.Slf4j;

/**
 * 给技能脚本准备 Python 3.12 和 Node 22。
 * Python 落在内置运行时根目录（显式配了数据目录时是数据目录下的 {@code runtime}），Node 新安装共用源码启动器的校验缓存。
 * 不进 git，打包 exe 时把这个目录一起拷走即可。
 */
@Component
@Slf4j
public class BundledRuntime implements SmartLifecycle {
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(2);
    private static final String PYTHON_RELEASE = "20261026";
    private static final String PYTHON_VERSION = "3.12.16";

    private final boolean provision;
    private final Path runtimeRoot;
    private volatile boolean running;

    public BundledRuntime(PilotDataDir dataDir, @Value("${pilot.runtime.provision:true}") boolean provision) {
        this.provision = provision;
        this.runtimeRoot = resolveRuntimeRoot(SourceLayout.workingDirectory(), dataDir.file("runtime"),
            dataDir.explicitlyConfigured());
    }

    @Override
    public void start() {
        if (provision) {
            ensure(runtimeRoot.resolve("python"), pythonArchive(), "python3", "python.exe");
        }
        publish(runtimeRoot);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    /** 实际使用的内置运行时根目录，单独暴露出来便于验证装配结果。 */
    Path runtimeRoot() {
        return runtimeRoot;
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
            if (run(parent, DOWNLOAD_TIMEOUT, "curl", "-fL", "--retry", "2", "--max-time", "180", "-o", tar.toString(),
                url) == 0 && Files.isRegularFile(tar)) {
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
        FileTrees.deleteBestEffort(unpack);
        try {
            Files.createDirectories(unpack);
        } catch (IOException e) {
            log.warn("解压目录创建失败：{}", e.toString());
            return;
        }
        List<String> extractCmd = tarExtractCommand(parent, tar, unpack);
        if (run(parent, EXTRACT_TIMEOUT, extractCmd.toArray(String[]::new)) != 0) {
            log.warn("解压 {} 失败", archive.name());
            return;
        }
        Path extracted = findTree(unpack, unixName, windowsName);
        if (extracted == null) {
            log.warn("压缩包里没有 {}：{}", unixName, unpack);
            return;
        }
        FileTrees.deleteBestEffort(dest);
        try {
            Files.move(extracted, dest);
        } catch (IOException e) {
            log.warn("放好 {} 失败：{}", archive.name(), e.toString());
            return;
        }
        FileTrees.deleteBestEffort(unpack);
        try {
            Files.deleteIfExists(tar);
        } catch (IOException ignored) {
            // 压缩包留着不影响使用
        }
        linkPython(dest);
    }

    private void publish(Path root) {
        List<String> bins = new ArrayList<>();
        Path bootstrap = bootstrapNode();
        if (bootstrap != null) {
            bins.add(bootstrap.getParent().toString());
        }
        addBin(bins, root.resolve("python"), "python3", "python.exe");
        Path node = findBin(root.resolve("node"), "node", "node.exe");
        if (node == null && bootstrap == null) {
            node = managedNode();
        }
        if (node != null) {
            bins.add(node.getParent().toString());
        }
        if (bins.isEmpty()) {
            return;
        }
        String value = RuntimeEnvironment.publishBundledBins(bins);
        log.info("技能脚本使用内置运行环境：{}", value);
    }

    private static Path bootstrapNode() {
        String value = System.getenv(RuntimeEnvironment.BOOTSTRAP_NODE);
        return value != null && !value.isBlank() && Files.isRegularFile(Path.of(value)) ? Path.of(value) : null;
    }

    private Path managedNode() {
        Path current = SourceLayout.workingDirectory();
        Path source = SourceLayout.serverRoot(current);
        Path root = source == null ? current : source;
        try {
            Path cache = SourceEnvironment.cache(root);
            var assets = new RuntimeAssets(cache, RuntimeAssets.manifest(root), !RuntimeEnvironment.offline(),
                message -> log.info("{}", message));
            var platform = BootstrapPlatform.current();
            var asset = assets.asset("node." + platform.key());
            Path installed = cache.resolve(asset.home());
            if (provision) {
                installed = assets.install(asset);
            } else if (!RuntimeAssets.verified(installed, asset)) {
                return null;
            }
            return nodeExecutable(installed, platform);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            if (provision) {
                log.warn("未能准备内置 Node.js，将使用系统环境：{}", e.getMessage());
            }
            return null;
        }
    }

    private static Path nodeExecutable(Path installed, BootstrapPlatform platform) {
        Path node = platform.windows() ? installed.resolve("node.exe") : installed.resolve("bin/node");
        if (Files.isRegularFile(node)) {
            return node;
        }
        log.warn("已校验的 Node.js 安装目录缺少可执行文件：{}", installed);
        return null;
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
        try (Stream<Path> children = Files.list(unpack)) {
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
        if (HostOs.isWindows()) {
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

    /**
     * 内置运行时根目录：显式配了 {@code pilot.data.dir} / {@code PILOT_DATA_DIR} 时优先用数据目录下的
     * {@code runtime}，避免在 worktree 里重复下载 Python；否则保持源码树仓库根 {@code .pilot/runtime}、
     * 不在源码树时当前目录 {@code .pilot/runtime} 的老行为。
     */
    static Path resolveRuntimeRoot(Path workingDir, Path dataRuntime, boolean dataDirConfigured) {
        Path current = workingDir.toAbsolutePath().normalize();
        if (dataDirConfigured) {
            return dataRuntime;
        }
        Path root = SourceLayout.serverRoot(current);
        return SourceLayout.bundledRuntime(root == null ? current : root);
    }

    private static Archive pythonArchive() {
        String triple = pythonTriple();
        String file = "cpython-" + PYTHON_VERSION + "+" + PYTHON_RELEASE + "-" + triple + "-install_only.tar.gz";
        String encoded = file.replace("+", "%2B");
        String origin =
            "https://github.com/astral-sh/python-build-standalone/releases/download/" + PYTHON_RELEASE + "/" + file;
        return new Archive("Python " + PYTHON_VERSION,
            List.of("https://registry.npmmirror.com/-/binary/python-build-standalone/" + PYTHON_RELEASE + "/" + encoded,
                origin));
    }

    private static String pythonTriple() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (HostOs.isWindows()) {
            return arm ? "aarch64-pc-windows-msvc" : "x86_64-pc-windows-msvc";
        }
        if (HostOs.isMac()) {
            return arm ? "aarch64-apple-darwin" : "x86_64-apple-darwin";
        }
        return arm ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu";
    }

    static List<String> tarExtractCommand(Path parent, Path tar, Path unpack) {
        String archiveArg = tar.startsWith(parent) ? parent.relativize(tar).toString() : tar.getFileName().toString();
        String unpackArg =
            unpack.startsWith(parent) ? parent.relativize(unpack).toString() : unpack.getFileName().toString();
        return List.of("tar", "-xf", archiveArg, "-C", unpackArg);
    }

    private static int run(Path directory, Duration timeout, String... command) {
        try {
            return ProcessSupport.runDiscarded(new ProcessBuilder(command).directory(directory.toFile()), timeout);
        } catch (IOException | TimeoutException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private record Archive(String name, List<String> urls) {
    }
}
