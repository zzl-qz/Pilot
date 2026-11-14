package com.pilot.server.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.core.runtime.ExecutablePath;
import com.pilot.core.runtime.bootstrap.RuntimeEnvironment;
import com.pilot.core.tool.ProcessSupport;
import com.pilot.server.runtime.NpmInstall;
import com.pilot.server.runtime.RuntimeMirrors;
import com.pilot.server.runtime.SourceLayout;

/**
 * 桌面壳（{@code pilot-desktop} 的 Electron）没装时自动安装，装好再打开窗口。
 *
 * <p>为什么自动装：桌面壳是 Electron 的二进制（约 250MB），不会随仓库提交，
 * 换机器 / 删掉 {@code node_modules} 之后第一次启动必然缺失。以前只打一行
 * 「请执行 npm --prefix pilot-desktop install」，得人工复制命令、等下载、再重启服务；
 * 浏览器运行时（{@code BrowserRuntime}）早就自己装了，桌面壳没有理由例外。
 *
 * <p>安装放在后台线程：Electron 下载要几分钟，不能把 Spring 启动卡住。
 * 装好之后回调打开窗口；装失败保留手动命令，服务照常可用（浏览器访问同一端口即可）。
 *
 * <p>首次安装失败后换备用 npm 与 Electron 镜像。{@code pilot.desktop.provision=false} 可关闭自动安装。
 */
@Component
public class DesktopShellInstaller {
    private static final Logger log = LoggerFactory.getLogger(DesktopShellInstaller.class);
    /** Electron 比 npm 包大得多，给足一次下载的时间。 */
    static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);
    /**
     * 手动安装提示。写「进目录再 npm install」而不是 {@code npm --prefix pilot-desktop install}：
     * npm 10 的 {@code --prefix} 仍会去读当前目录的 package.json，在仓库根执行会直接 ENOENT 失败。
     */
    static final String MANUAL = "手动安装：进入 pilot-desktop 目录执行 npm install";

    private final boolean provision;
    private final Runner runner;
    private final RuntimeMirrors mirrors;
    /** 同一进程里只装一次；重复调用（定时器、手动重试）直接返回「正在安装」。 */
    private final AtomicBoolean installing = new AtomicBoolean();

    @Autowired
    public DesktopShellInstaller(@Value("${pilot.desktop.provision:true}") boolean provision, RuntimeMirrors mirrors) {
        this(provision, DesktopShellInstaller::run, mirrors);
    }

    DesktopShellInstaller(boolean provision, Runner runner, RuntimeMirrors mirrors) {
        this.provision = provision;
        this.runner = runner;
        this.mirrors = mirrors;
    }

    /** 命令执行器：返回退出码。测试里换成不真装的实现。 */
    @FunctionalInterface
    interface Runner {
        int run(Path directory, List<String> command, Map<String, String> environment, Path logFile, Duration timeout);
    }

    /**
     * 桌面壳已经装好返回 {@code null}；否则返回一句话说明（正在后台安装，或为什么装不了）。
     * 后台安装完成后回调 {@code onReady}（用于打开窗口）。
     */
    public String ensureInstalled(Path root, Path browserHome, Runnable onReady) {
        if (Files.isExecutable(DesktopWindowLauncher.electronBinary(root))) {
            return null;
        }
        Path desktop = root.resolve("pilot-desktop");
        if (!Files.isRegularFile(desktop.resolve("package.json"))) {
            return "桌面壳目录不完整（缺少 pilot-desktop/package.json），无法安装。";
        }
        if (!provision) {
            return "桌面壳尚未安装，且自动安装已关闭（pilot.desktop.provision=false）。" + MANUAL;
        }
        if (!installing.compareAndSet(false, true)) {
            return "桌面壳正在安装，装好后会自动打开窗口。";
        }
        Path npm = npm(root);
        if (npm == null) {
            installing.set(false);
            return "桌面壳尚未安装，也没有找到 npm，无法自动安装。" + MANUAL;
        }
        Path logFile = logFile(browserHome);
        Thread thread = new Thread(() -> install(root, desktop, npm, logFile, onReady), "pilot-desktop-install");
        thread.setDaemon(true);
        thread.start();
        return "桌面壳（Electron）尚未安装，正在后台安装（首次约几分钟，日志 " + logFile + "），装好会自动打开窗口。" + MANUAL;
    }

    /** 正在安装？给测试与日志用。 */
    public boolean installing() {
        return installing.get();
    }

    private void install(Path root, Path desktop, Path npm, Path logFile, Runnable onReady) {
        try {
            List<String> command = NpmInstall.command(npm, List.of());
            int attempt = 0;
            for (Map<String, String> overrides : mirrors.npmAttempts(mirrors.electronEnvironment())) {
                attempt++;
                int code = runner.run(desktop, command, environment(npm, overrides), logFile, INSTALL_TIMEOUT);
                if (ready(root, onReady)) {
                    log.info("桌面壳安装完成（第 {} 次尝试），正在打开窗口", attempt);
                    return;
                }
                log.warn("桌面壳安装未成功（第 {} 次尝试，退出码 {}），日志 {}", attempt, code, logFile);
            }
            if (downloadBinaryOnly(root, desktop, npm, logFile, onReady)) {
                log.info("桌面壳安装完成（镜像补下载二进制），正在打开窗口");
                return;
            }
            log.warn("桌面壳自动安装失败，{}（日志 {}）", MANUAL, logFile);
        } finally {
            installing.set(false);
        }
    }

    /**
     * 只补 Electron 二进制：npm 已经装过一遍之后，第二次 {@code npm install} 认为依赖完整，
     * 不会重跑 electron 的 postinstall——二进制却因为第一次下载失败根本没落地。
     * 这时直接跑 electron 自带的 install.js（官方源已经试过，这里走镜像）。
     */
    private boolean downloadBinaryOnly(Path root, Path desktop, Path npm, Path logFile, Runnable onReady) {
        Path script = desktop.resolve("node_modules").resolve("electron").resolve("install.js");
        Path node = sibling(npm, "node");
        if (Files.isExecutable(DesktopWindowLauncher.electronBinary(root)) || !Files.isRegularFile(script)
            || node == null) {
            return false;
        }
        log.info("Electron 包已装好但二进制缺失，直接用镜像补下载");
        runner.run(desktop, List.of(node.toString(), script.toString()),
            environment(npm, mirrors.electronEnvironment()), logFile, INSTALL_TIMEOUT);
        return ready(root, onReady);
    }

    /** 二进制到位了就回调开窗。 */
    private boolean ready(Path root, Runnable onReady) {
        if (!Files.isExecutable(DesktopWindowLauncher.electronBinary(root))) {
            return false;
        }
        onReady.run();
        return true;
    }

    /** npm 所在目录进 PATH（npm 要能找到同目录的 node），再叠加本次尝试的镜像变量。 */
    private static Map<String, String> environment(Path npm, Map<String, String> extra) {
        Map<String, String> environment = new LinkedHashMap<>(extra);
        List<String> bins = new ArrayList<>(
            List.of(npm.getParent().toString(), Path.of(System.getProperty("java.home"), "bin").toString()));
        String mobileBin = RuntimeEnvironment.mobileBinPath();
        if (!mobileBin.isBlank()) {
            bins.add(mobileBin);
        }
        String inherited = ExecutablePath.inherited(System.getenv());
        if (!inherited.isBlank()) {
            bins.add(inherited);
        }
        environment.put("PATH", String.join(File.pathSeparator, bins));
        return environment;
    }

    /** 与 npm 同目录的 node（Electron 只接受 Node 22+，就用装它的那个 Node 补二进制）。 */
    private static Path sibling(Path npm, String name) {
        Path candidate = npm.getParent().resolve(isWindows() ? name + ".exe" : name);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /** 本机 npm 的位置；测试可覆盖成固定值。 */
    Path npm(Path root) {
        return npm(root, RuntimeEnvironment.bundledBinPath(), ExecutablePath.inherited(System.getenv()));
    }

    /**
     * npm 的查找顺序：内置 Node 22 → PATH。
     *
     * <p>内置运行时必须优先：Electron 45 要求 Node >= 22.12，机器上的系统 npm 常常是 Node 20，
     * 用它装会只装包、不下载二进制（实测 node 20 + npm 10 装完没有 dist）。
     */
    static Path npm(Path root, String runtimeBin, String path) {
        boolean windows = isWindows();
        var bundled = ExecutablePath.directories(runtimeBin);
        var legacy = Stream.of(SourceLayout.bundledRuntime(root).resolve("node"));
        var inherited = ExecutablePath.directories(path);
        return ExecutablePath
            .candidates(Stream.concat(Stream.concat(bundled, legacy), inherited), windows ? "npm.cmd" : "npm")
            .filter(Files::isRegularFile).filter(candidate -> windows || Files.isExecutable(candidate)).findFirst()
            .orElse(null);
    }

    private static Path logFile(Path browserHome) {
        Path file = browserHome.resolve("desktop-install.log");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.debug("桌面壳安装日志目录创建失败：{}", e.getMessage());
        }
        return file;
    }

    private static int run(Path directory, List<String> command, Map<String, String> environment, Path logFile,
        Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        RuntimeMirrors.applyEnvironment(builder.environment(), environment);
        // 直接补下载与 npm 安装共用锁定包内的校验值。
        builder.environment().keySet().removeIf(name -> "electron_use_remote_checksums".equalsIgnoreCase(name)
            || "npm_config_electron_use_remote_checksums".equalsIgnoreCase(name));
        return ProcessSupport.runLogged(builder, logFile, timeout, log);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
