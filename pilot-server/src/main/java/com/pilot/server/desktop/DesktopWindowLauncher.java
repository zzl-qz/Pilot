package com.pilot.server.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务起来后打开桌面窗口。浏览器访问同一个端口仍然可用。
 * 桌面壳没装时交给 {@link DesktopShellInstaller} 后台安装，装好自动开窗。
 * 测试和打包后的 jar 不打开窗口。
 */
@Component
public class DesktopWindowLauncher {
    private static final Logger log = LoggerFactory.getLogger(DesktopWindowLauncher.class);
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    private final Environment environment;
    private final DesktopShellInstaller installer;

    public DesktopWindowLauncher(Environment environment, DesktopShellInstaller installer) {
        this.environment = environment;
        this.installer = installer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        if (!environment.getProperty("pilot.desktop.launch", Boolean.class, true)) {
            return;
        }
        report(launch());
    }

    private void report(LaunchResult result) {
        if (result.started()) {
            log.info("已打开桌面窗口 {}", result.message());
        } else {
            log.info("{}", result.message());
        }
    }

    /** 后台装完桌面壳之后再走一遍完整检查（可能期间已经有人开过窗口）。 */
    private void launchAfterInstall() {
        report(launch());
    }

    /** 服务启动时打开桌面窗口。窗口已经在就不再开第二个。 */
    public LaunchResult launch() {
        try {
            Path root = repositoryRoot();
            if (root == null || !runningFromClasses()) {
                return LaunchResult.unavailable("当前不是从源码启动，没有可打开的桌面窗口。");
            }
            if (alreadyOpen(root)) {
                return LaunchResult.already("桌面窗口已经在运行，请切换到那个窗口使用内置浏览器。");
            }
            Path logFile = root.resolve(".pilot").resolve("browser").resolve("desktop-launch.log");
            Files.createDirectories(logFile.getParent());
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
            Path electron = electronBinary(root);
            if (!Files.isExecutable(electron)) {
                String pending = installer.ensureInstalled(root, this::launchAfterInstall);
                if (pending != null) {
                    return LaunchResult.installing(pending);
                }
                electron = electronBinary(root);
            }
            String url = "http://127.0.0.1:" + port();
            electron = prepareDesktopBinary(root, electron, logFile);
            ProcessBuilder builder = new ProcessBuilder(electron.toString(), root.resolve("pilot-desktop").toString());
            builder.directory(root.resolve("pilot-desktop").toFile());
            builder.environment().remove("ELECTRON_RUN_AS_NODE");
            builder.environment().put("PILOT_URL", url);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.start();
            return LaunchResult.started("已打开桌面窗口。内置浏览器在那个窗口里。");
        } catch (IOException e) {
            return LaunchResult.failed("桌面窗口没有打开：" + e.getMessage());
        }
    }

    public record LaunchResult(String status, String message) {
        public boolean started() {
            return "started".equals(status);
        }

        static LaunchResult started(String message) {
            return new LaunchResult("started", message);
        }

        static LaunchResult already(String message) {
            return new LaunchResult("already", message);
        }

        static LaunchResult unavailable(String message) {
            return new LaunchResult("unavailable", message);
        }

        /** 桌面壳缺失且已触发后台安装：窗口还没开，但也不是失败。 */
        static LaunchResult installing(String message) {
            return new LaunchResult("installing", message);
        }

        static LaunchResult failed(String message) {
            return new LaunchResult("failed", message);
        }
    }

    private String port() {
        return environment.getProperty("local.server.port",
                environment.getProperty("server.port", "9999"));
    }

    /** 开发态才打开：从 classes 目录启动，并且能找到源码仓库。 */
    static boolean runningFromClasses() {
        try {
            var source = DesktopWindowLauncher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return false;
            }
            String path = source.getLocation().getPath();
            return path != null && path.contains("target/classes");
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Path repositoryRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isRegularFile(dir.resolve("pilot-server").resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("pilot-desktop"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    static Path electronBinary(Path root) {
        Path dist = root.resolve("pilot-desktop").resolve("node_modules").resolve("electron").resolve("dist");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return dist.resolve("Electron.app").resolve("Contents").resolve("MacOS").resolve("Electron");
        }
        if (os.contains("win")) {
            return dist.resolve("electron.exe");
        }
        return dist.resolve("electron");
    }

    /** 使用 Electron 自带的 Node 准备开发壳，IDEA 不需要额外配置 node 的 PATH。 */
    static Path prepareDesktopBinary(Path root, Path electron, Path logFile) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return electron;
        }
        ProcessBuilder prepare = new ProcessBuilder(electron.toString(),
                root.resolve("pilot-desktop").resolve("brand-host.mjs").toString());
        prepare.directory(root.resolve("pilot-desktop").toFile());
        prepare.environment().put("ELECTRON_RUN_AS_NODE", "1");
        prepare.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = prepare.start();
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("准备 Pilot 开发壳超时，请查看 " + logFile);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0 || output.isEmpty()) {
                throw new IOException("准备 Pilot 开发壳失败，请查看 " + logFile);
            }
            Path binary = Path.of(output);
            if (!Files.isExecutable(binary)) {
                throw new IOException("Pilot 开发壳不可执行：" + binary);
            }
            return binary;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("准备 Pilot 开发壳被中断", e);
        }
    }

    static boolean alreadyOpen(Path root) {
        Path file = root.resolve(".pilot").resolve("browser").resolve("desktop-browser.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Matcher matcher = PID.matcher(Files.readString(file));
            if (!matcher.find()) {
                return false;
            }
            return ProcessHandle.of(Long.parseLong(matcher.group(1))).map(ProcessHandle::isAlive).orElse(false);
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }
}
