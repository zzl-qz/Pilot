package com.pilot.server.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.pilot.core.util.HostOs;
import com.pilot.server.browser.BrowserHome;
import com.pilot.server.browser.BrowserRuntime;
import com.pilot.server.runtime.SourceLayout;

/**
 * 服务起来后打开桌面窗口（Electron），浏览器访问同一个端口仍然是同一个 Web 工作台。
 * 桌面壳没装时交给 {@link DesktopShellInstaller} 后台安装，装好自动开窗。
 *
 * <p>官方启动脚本、打包后的 jar、IDE / spring-boot:run 走同一条开窗链路，
 * 不再按 code source 路径区分；只有确实没有图形桌面会话时才明确降级到 Web 工作台。
 */
@Component
public class DesktopWindowLauncher {
    private static final Logger log = LoggerFactory.getLogger(DesktopWindowLauncher.class);
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");
    private static final Pattern CONTROL_PORT = Pattern.compile("\"controlPort\"\\s*:\\s*(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern START_MILLIS = Pattern.compile("\"startMillis\"\\s*:\\s*(\\d+)");
    /** 墙钟在两个进程里可能略有偏差，但足够区分系统复用 PID 后的另一个进程。 */
    private static final long START_TOLERANCE_MILLIS = 10_000L;
    private static final Duration WINDOW_TIMEOUT = Duration.ofSeconds(20);

    private final Environment environment;
    private final DesktopShellInstaller installer;
    private final BrowserHome browserHome;

    public DesktopWindowLauncher(Environment environment, DesktopShellInstaller installer, BrowserHome browserHome) {
        this.environment = environment;
        this.installer = installer;
        this.browserHome = browserHome;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        if (!environment.getProperty("pilot.desktop.launch", Boolean.class, true)) {
            log.info("桌面启动诊断：已关闭（pilot.desktop.launch=false）；当前服务不会自动启动 Electron");
            return;
        }
        log.info("桌面启动诊断：已启用，系统={} {}，服务端口={}", System.getProperty("os.name", "unknown"),
            System.getProperty("os.arch", "unknown"), port());
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
        return launch(BrowserRuntime::desktopAvailable);
    }

    /** 桌面会话能力可注入，便于在没有图形会话的平台上做单元测试。 */
    LaunchResult launch(BooleanSupplier desktopAvailable) {
        Path logFile = browserHome.directory().resolve("desktop-launch.log");
        try {
            Path root = repositoryRoot();
            if (root == null) {
                return LaunchResult.unavailable("没有找到 Pilot 源码目录，桌面窗口不可用；可访问 " + webUrl() + " 使用 Web 工作台。");
            }
            if (!desktopAvailable.getAsBoolean()) {
                return LaunchResult.unavailable("当前没有可用的图形桌面，桌面窗口无法打开；请访问 " + webUrl() + " 使用 Web 工作台。");
            }
            if (alreadyOpen(browserHome.desktopEndpoint())) {
                return LaunchResult.already("桌面窗口已经在运行，请切换到那个窗口使用内置浏览器。");
            }
            Files.createDirectories(logFile.getParent());
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
            Path electron = electronBinary(root);
            if (!Files.isExecutable(electron)) {
                String pending = installer.ensureInstalled(root, browserHome.directory(), this::launchAfterInstall);
                if (pending != null) {
                    return LaunchResult.installing(pending);
                }
                electron = electronBinary(root);
            }
            String url = webUrl();
            electron = prepareDesktopBinary(root, electron, logFile);
            ProcessBuilder builder = new ProcessBuilder(electron.toString(), root.resolve("pilot-desktop").toString());
            builder.directory(root.resolve("pilot-desktop").toFile());
            builder.environment().remove("ELECTRON_RUN_AS_NODE");
            builder.environment().put("PILOT_URL", url);
            builder.environment().put("PILOT_BROWSER_HOME", browserHome.directory().toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Process process = builder.start();
            watchWindow(process, logFile, url);
            return LaunchResult.started("正在打开桌面窗口；Web 工作台同样可用：" + url + "。");
        } catch (IOException e) {
            return LaunchResult
                .failed("桌面窗口没有打开：" + e.getMessage() + "；请访问 " + webUrl() + " 使用 Web 工作台，或查看 " + logFile + "。");
        }
    }

    private String webUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** 不阻塞服务启动：后台确认窗口真的就绪，失败时给出明确原因和可用的替代操作。 */
    private void watchWindow(Process process, Path logFile, String url) {
        Thread thread = new Thread(() -> {
            long deadline = System.nanoTime() + WINDOW_TIMEOUT.toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    if (alreadyOpen(browserHome.desktopEndpoint())) {
                        return;
                    }
                    if (!process.isAlive()) {
                        log.warn("桌面窗口进程已退出（退出码 {}），请查看 {}；可访问 {} 使用 Web 工作台。", process.exitValue(), logFile, url);
                        return;
                    }
                    Thread.sleep(200);
                }
                log.warn("桌面窗口在 {} 秒内没有就绪，请查看 {}；可访问 {} 使用 Web 工作台。", WINDOW_TIMEOUT.toSeconds(), logFile, url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pilot-desktop-window-check");
        thread.setDaemon(true);
        thread.start();
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
        return environment.getProperty("local.server.port", environment.getProperty("server.port", "9999"));
    }

    static Path repositoryRoot() {
        return SourceLayout.desktopRoot(SourceLayout.workingDirectory());
    }

    static Path electronBinary(Path root) {
        Path dist = root.resolve("pilot-desktop").resolve("node_modules").resolve("electron").resolve("dist");
        if (HostOs.isMac()) {
            return dist.resolve("Electron.app").resolve("Contents").resolve("MacOS").resolve("Electron");
        }
        if (HostOs.isWindows()) {
            return dist.resolve("electron.exe");
        }
        return dist.resolve("electron");
    }

    /** 使用 Electron 自带的 Node 准备开发壳，IDEA 不需要额外配置 node 的 PATH。 */
    static Path prepareDesktopBinary(Path root, Path electron, Path logFile) throws IOException {
        if (!HostOs.isMac()) {
            return electron;
        }
        ProcessBuilder prepare =
            new ProcessBuilder(electron.toString(), root.resolve("pilot-desktop").resolve("brand-host.mjs").toString());
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

    static boolean alreadyOpen(Path file) {
        return alreadyOpen(file, pid -> ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
            .flatMap(handle -> handle.info().command()).orElse(null));
    }

    /**
     * 残留端点不能再只凭「PID 还活着」判定：Windows 上 PID 可能被系统复用给 conhost 等无关进程。
     * 端点必须完整、进程必须是桌面壳，且新格式记录的启动时间要与进程一致。
     */
    static boolean alreadyOpen(Path file, LongFunction<String> commandOf) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            String json = Files.readString(file);
            Matcher matcher = PID.matcher(json);
            if (!matcher.find()) {
                discard(file);
                return false;
            }
            long pid = Long.parseLong(matcher.group(1));
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                discard(file);
                return false;
            }
            String command = commandOf.apply(pid);
            if (command != null && !isDesktopShell(command)) {
                discard(file);
                return false;
            }
            if (!CONTROL_PORT.matcher(json).find() || !TOKEN.matcher(json).find()) {
                discard(file);
                return false;
            }
            Matcher start = START_MILLIS.matcher(json);
            if (start.find()) {
                Optional<Instant> actual = handle.get().info().startInstant();
                if (actual.isPresent() && Math
                    .abs(actual.get().toEpochMilli() - Long.parseLong(start.group(1))) > START_TOLERANCE_MILLIS) {
                    discard(file);
                    return false;
                }
            }
            return true;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    /** 统一按路径分隔符归一，Windows / macOS / Linux 的桌面壳可执行文件都能识别。 */
    static boolean isDesktopShell(String command) {
        String name = command.toLowerCase(Locale.ROOT).replace('\\', '/');
        return name.contains("electron") || name.contains("pilot.app") || name.endsWith("/pilot")
            || name.contains("pilot.exe");
    }

    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 只读或并发写入时保留即可，下次启动会再校验一次
        }
    }
}
