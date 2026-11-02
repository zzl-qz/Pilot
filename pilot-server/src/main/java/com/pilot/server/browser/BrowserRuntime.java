package com.pilot.server.browser;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserEventListener;
import com.pilot.core.browser.PlaywrightBackend;
import com.pilot.core.tool.Availability;
import com.pilot.server.config.PilotDataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器运行时的装配与生命周期。
 *
 * <p>启动时在后台线程里依次完成：释放 sidecar 脚本 → 用固定版本的 {@code playwright-core}
 * 安装依赖（首次）→ 与 sidecar 握手并真的启动一次浏览器。只有握手成功，{@link #availability()}
 * 才返回可用，{@code browser} 工具才会向模型声明；失败时原因会保留下来原样告知模型。
 *
 * <p>浏览器来源：Chromium 完整收进仓库的 {@code .pilot/browser/browsers}
 * （{@code PLAYWRIGHT_BROWSERS_PATH} 指向该处，下载也落这里，删 {@code .pilot} 即全量清理），
 * 优先于系统 Chrome / Edge 使用——版本配套、行为跨机器一致；
 * {@code pilot.browser.prefer-bundled=false} 可反转。
 *
 * <p>默认后台运行（{@code pilot.browser.headless=true}）：智能体使用浏览器时不开任何窗口，
 * 画面只走侧边栏镜像（CDP screencast 在无头下已实测可用）；{@code pilot.browser.headless=false}
 * 切回有头窗口（共看同一页面、手动接管）。无桌面会话时显式有头会自动降级无头。
 * 空闲回收：无头 10 分钟，有头默认不回收（可用 {@code pilot.browser.idle-shutdown-seconds} 显式控制）。
 *
 * <p>运行时目录放在仓库的 {@code .pilot/browser}（与内置 Node/Python 同处，不进 git），找不到仓库根则用数据目录。
 */
@Component
public class BrowserRuntime implements BrowserBackend, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BrowserRuntime.class);
    /** 固定版本：sidecar 协议与快照脚本只针对这个版本验证过。 */
    static final String PLAYWRIGHT_VERSION = "1.64.1";
    private static final String SCRIPT_RESOURCE = "browser/browser-sidecar.mjs";
    private static final String SCRIPT_NAME = "browser-sidecar.mjs";
    /** npmmirror 的 Playwright 浏览器镜像，与 npm 换源策略一致。 */
    private static final String CHROMIUM_MIRROR = "https://npmmirror.com/mirrors/playwright";

    private final PilotDataDir dataDir;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean provision;
    private final boolean downloadChromium;
    /** 配置要求的有头/无头；实际生效值还要叠加无桌面降级。 */
    private final boolean headlessConfigured;
    private final boolean preferBundled;
    private final String browsersDir;
    /** >=0：显式秒数；-1：自动（无头 10 分钟，有头不回收）。 */
    private final long idleShutdownSeconds;

    private volatile PlaywrightBackend backend;
    private volatile String pendingReason = "浏览器运行时尚未启动";
    private volatile boolean running;
    private volatile Thread worker;
    /** 最近一次装配实际生效的 headless（含无桌面降级）；镜像面板据此隐藏「置前窗口」。 */
    private volatile boolean lastHeadless = true;
    /** 镜像订阅方注册的监听者：backend 异步重建时随实例转移。 */
    private volatile BrowserEventListener eventListener;
    /** 用户接管开关（会话级内存态，不落库）：BrowserTool 据此拦截交互类动作。 */
    private final ConcurrentMap<String, Boolean> interactionsPaused = new ConcurrentHashMap<>();

    public BrowserRuntime(PilotDataDir dataDir, ObjectMapper mapper,
                          @Value("${pilot.browser.enabled:true}") boolean enabled,
                          @Value("${pilot.browser.provision:true}") boolean provision,
                          @Value("${pilot.browser.download-chromium:true}") boolean downloadChromium,
                          @Value("${pilot.browser.headless:true}") boolean headless,
                          @Value("${pilot.browser.prefer-bundled:true}") boolean preferBundled,
                          @Value("${pilot.browser.browsers-dir:}") String browsersDir,
                          @Value("${pilot.browser.idle-shutdown-seconds:-1}") long idleShutdownSeconds) {
        this.dataDir = dataDir;
        this.mapper = mapper;
        this.enabled = enabled;
        this.provision = provision;
        this.downloadChromium = downloadChromium;
        this.headlessConfigured = headless;
        this.preferBundled = preferBundled;
        this.browsersDir = browsersDir == null ? "" : browsersDir.strip();
        this.idleShutdownSeconds = idleShutdownSeconds;
    }

    @Override
    public Availability availability() {
        if (!enabled) {
            return Availability.unavailable("浏览器运行时已在配置中关闭（pilot.browser.enabled=false）");
        }
        PlaywrightBackend current = backend;
        return current == null ? Availability.unavailable(pendingReason) : current.availability();
    }

    @Override
    public Reply execute(String sessionId, Map<String, Object> command, Duration timeout) {
        PlaywrightBackend current = backend;
        if (current == null) {
            throw new BrowserException("浏览器当前不可用：" + availability().reason());
        }
        return current.execute(sessionId, command, timeout);
    }

    @Override
    public void closeSession(String sessionId) {
        interactionsPaused.remove(sessionId); // 会话关闭即复位接管开关
        PlaywrightBackend current = backend;
        if (current != null) current.closeSession(sessionId);
    }

    /** 用户接管：暂停/恢复智能体的页面交互动作（观察类不受影响）；内存态，重启即清零。 */
    public void setInteractionsPaused(String sessionId, boolean paused) {
        if (paused) {
            interactionsPaused.put(sessionId, Boolean.TRUE);
        } else {
            interactionsPaused.remove(sessionId);
        }
    }

    @Override
    public boolean interactionsPaused(String sessionId) {
        return interactionsPaused.containsKey(sessionId);
    }

    @Override
    public void setEventListener(BrowserEventListener listener) {
        this.eventListener = listener;
        PlaywrightBackend current = backend;
        if (current != null) current.setEventListener(listener);
    }

    @Override
    public void mirrorControl(String sessionId, boolean start, int maxWidth, int quality) {
        PlaywrightBackend current = backend;
        if (current != null) current.mirrorControl(sessionId, start, maxWidth, quality);
    }

    @Override
    public void focusPage(String sessionId) {
        PlaywrightBackend current = backend;
        if (current != null) current.focusPage(sessionId);
    }

    /** sidecar 是否声明了镜像事件能力（握手协商结果；未就绪时为 false）。 */
    public boolean mirrorCapable() {
        PlaywrightBackend current = backend;
        return current != null && current.mirrorCapable();
    }

    /** 最近一次装配实际生效的 headless（含无桌面降级）：镜像面板据此隐藏「置前窗口」。 */
    public boolean headless() {
        return lastHeadless;
    }

    @Override
    public void start() {
        running = true;
        if (!enabled) return;
        pendingReason = "浏览器运行时正在准备（首次使用需要安装依赖，请稍后再试）";
        Thread thread = new Thread(this::prepare, "pilot-browser-provision");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        PlaywrightBackend current = backend;
        backend = null;
        if (current != null) current.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void prepare() {
        try {
            Path home = home();
            Files.createDirectories(home);
            Path script = home.resolve(SCRIPT_NAME);
            writeIfChanged(script);
            writePackageJson(home);
            Path node = findNode();
            if (node == null) {
                pendingReason = "没有找到 Node.js（浏览器验证需要 Node 22）。安装 Node，或让内置运行时下载完成后重启服务。";
                log.warn(pendingReason);
                return;
            }
            Path playwright = home.resolve("node_modules").resolve("playwright-core");
            if (!Files.isDirectory(playwright)) {
                if (!provision) {
                    pendingReason = "浏览器依赖 playwright-core 尚未安装，且自动安装已关闭（pilot.browser.provision=false）";
                    return;
                }
                if (!install(home, node)) {
                    pendingReason = "安装 playwright-core 失败（需要能访问 npm 源）。日志：" + home.resolve("install.log");
                    return;
                }
            }
            boolean headless = resolveHeadless();
            lastHeadless = headless;
            PlaywrightBackend candidate = new PlaywrightBackend(node, script, home, mapper,
                    idleMillis(headless), sidecarEnvironment(home, headless));
            candidate.setEventListener(eventListener);
            Availability result = candidate.probe();
            if (!result.available() && provision && downloadChromium
                    && result.reason().contains("没有找到可用的")
                    && installChromium(home, node)) {
                result = candidate.probe();
            }
            // 即使探测失败也保留 backend：之后用户装好浏览器，availability() 会自动重测。
            backend = candidate;
            if (!running) {
                candidate.close();
                backend = null;
            }
        } catch (IOException | RuntimeException e) {
            pendingReason = "浏览器运行时准备失败：" + e.getMessage();
            log.warn(pendingReason, e);
        }
    }

    private boolean install(Path home, Path node) {
        Path npm = sibling(node, "npm");
        if (npm == null) {
            log.warn("没有找到 npm，无法安装 playwright-core");
            return false;
        }
        log.info("第一次使用浏览器验证，正在安装 playwright-core@{}", PLAYWRIGHT_VERSION);
        List<List<String>> attempts = List.of(
                List.of(npm.toString(), "install", "--no-audit", "--no-fund", "--omit=dev"),
                List.of(npm.toString(), "install", "--no-audit", "--no-fund", "--omit=dev",
                        "--registry=https://registry.npmmirror.com"));
        for (List<String> command : attempts) {
            if (run(home, node, Duration.ofMinutes(5), command, Map.of()) == 0
                    && Files.isDirectory(home.resolve("node_modules").resolve("playwright-core"))) {
                return true;
            }
            log.info("安装未成功，换下一个 npm 源重试");
        }
        return false;
    }

    private boolean installChromium(Path home, Path node) {
        Path cli = home.resolve("node_modules").resolve("playwright-core").resolve("cli.js");
        if (!Files.isRegularFile(cli)) return false;
        Path browsers = browsersPath(home);
        log.info("本机没有可用的浏览器，正在下载内置 Chromium（完整版约 400MB，仅此一次，落盘 {}）", browsers);
        // 只装完整版：sidecar 一律显式传 executablePath（无头降级也是完整版跑无头模式），
        // playwright 1.49+ 附带的 headless-shell 用不上，--no-shell 省掉这份下载。
        // 官方 CDN 失败换 npmmirror 镜像，与 npm 双源策略一致。
        List<String> command = List.of(node.toString(), cli.toString(), "install", "chromium", "--no-shell");
        Map<String, String> base = chromiumDownloadEnvironment(browsers);
        if (run(home, node, Duration.ofMinutes(15), command, base) == 0 && hasChromium(browsers)) {
            return true;
        }
        log.info("官方源下载未成功，换 npmmirror 镜像重试");
        Map<String, String> mirror = new LinkedHashMap<>(base);
        mirror.put("PLAYWRIGHT_DOWNLOAD_HOST", CHROMIUM_MIRROR);
        return run(home, node, Duration.ofMinutes(15), command, mirror) == 0 && hasChromium(browsers);
    }

    /** Chromium 下载/安装命令的环境变量：落盘目录收进 .pilot，不污染全局缓存。 */
    private Map<String, String> chromiumDownloadEnvironment(Path browsers) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PLAYWRIGHT_BROWSERS_PATH", browsers.toString());
        return env;
    }

    /** @return PLAYWRIGHT_BROWSERS_PATH 下是否已有 chromium-* 目录（安装成功的粗校验） */
    private static boolean hasChromium(Path browsers) {
        if (!Files.isDirectory(browsers)) return false;
        try (java.util.stream.Stream<Path> entries = Files.list(browsers)) {
            return entries.anyMatch(path -> Files.isDirectory(path)
                    && path.getFileName().toString().startsWith("chromium-"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 有头/无头的实际生效值：配置值叠加无桌面降级。 */
    private boolean resolveHeadless() {
        if (!headlessConfigured && !desktopAvailable()) {
            log.warn("配置为有头，但当前环境没有桌面会话（Windows 服务会话 / 无 DISPLAY），自动降级为无头运行");
            return true;
        }
        return headlessConfigured;
    }

    private static boolean desktopAvailable() {
        return desktopAvailable(System.getProperty("os.name"),
                System.getenv("SESSIONNAME"), System.getenv("DISPLAY"), System.getenv("WAYLAND_DISPLAY"));
    }

    /**
     * 有没有可用的桌面会话——有头窗口能不能真开出来只取决于操作系统层面。
     *
     * <p><b>不能用 {@code GraphicsEnvironment.isHeadless()}：</b>Spring Boot 启动时会把
     * {@code java.awt.headless} 统一设成 {@code true}（Web 应用的标准做法，防止 AWT 意外初始化），
     * 该标志只约束 JVM 内的 AWT，与子进程 Chromium 能否开窗口无关——用它判断会在
     * 所有 Spring Boot 部署里误降级，本地有桌面的 Windows 也一样中招（实测踩过）。
     *
     * <p>真实信号：Windows 服务会话（Session 0，{@code SESSIONNAME=Service}）没有交互桌面，
     * 控制台与 RDP 会话都有，变量缺失时按有桌面处理（本地 Web 应用是主流形态）；
     * Linux 看 {@code DISPLAY} / {@code WAYLAND_DISPLAY}（CI、纯 SSH 环境正确降级）；
     * macOS 本地运行常在桌面，不额外探测。
     */
    static boolean desktopAvailable(String osName, String sessionName, String display, String waylandDisplay) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return !"Service".equalsIgnoreCase(sessionName);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return true;
        }
        return display != null || waylandDisplay != null;
    }

    /** 空闲回收策略：显式秒数优先；自动模式下无头 10 分钟，有头不回收（用户可能正看着窗口）。 */
    private long idleMillis(boolean headless) {
        if (idleShutdownSeconds >= 0) return idleShutdownSeconds * 1000L;
        return headless ? 10 * 60_000L : 0L;
    }

    /** sidecar 进程的注入环境：浏览器选型与有头/无头全部由 Java 配置驱动。 */
    private Map<String, String> sidecarEnvironment(Path home, boolean headless) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PLAYWRIGHT_BROWSERS_PATH", browsersPath(home).toString());
        env.put("PILOT_BROWSER_HEADLESS", headless ? "true" : "false");
        if (!preferBundled) env.put("PILOT_BROWSER_PREFER_BUNDLED", "false");
        return env;
    }

    /** 内置 Chromium 落盘目录：默认在运行时目录下的 browsers/，可用配置改到别处。 */
    private Path browsersPath(Path home) {
        if (browsersDir.isEmpty()) return home.resolve("browsers");
        Path configured = Path.of(browsersDir);
        return configured.isAbsolute() ? configured : Path.of(".").toAbsolutePath().resolve(configured);
    }

    private int run(Path home, Path node, Duration timeout, List<String> command, Map<String, String> environment) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        String path = builder.environment().getOrDefault("PATH", "");
        builder.environment().put("PATH", node.getParent() + File.pathSeparator + path);
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("install.log").toFile()));
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                log.warn("命令超时：{}", String.join(" ", command));
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            log.warn("命令无法执行：{}（{}）", String.join(" ", command), e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void writeIfChanged(Path script) throws IOException {
        byte[] bundled;
        try (InputStream in = new ClassPathResource(SCRIPT_RESOURCE).getInputStream()) {
            bundled = in.readAllBytes();
        }
        if (Files.isRegularFile(script) && java.util.Arrays.equals(Files.readAllBytes(script), bundled)) return;
        Files.copy(new java.io.ByteArrayInputStream(bundled), script, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writePackageJson(Path home) throws IOException {
        String json = """
                {
                  "name": "pilot-browser-sidecar",
                  "private": true,
                  "type": "module",
                  "dependencies": {
                    "playwright-core": "%s"
                  }
                }
                """.formatted(PLAYWRIGHT_VERSION);
        Path file = home.resolve("package.json");
        if (Files.isRegularFile(file) && Files.readString(file).equals(json)) return;
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private Path home() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isRegularFile(dir.resolve("pilot-server").resolve("pom.xml"))) {
                return dir.resolve(".pilot").resolve("browser");
            }
            dir = dir.getParent();
        }
        return dataDir.file("browser");
    }

    /** 内置运行时优先，其次 PATH，最后常见安装位置。 */
    private static Path findNode() {
        List<String> dirs = new ArrayList<>();
        String bundled = System.getProperty("pilot.runtime.bin", "");
        dirs.addAll(List.of(bundled.split(File.pathSeparator)));
        String path = System.getenv("PATH");
        if (path != null) dirs.addAll(List.of(path.split(File.pathSeparator)));
        dirs.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"));
        for (String dir : dirs) {
            if (dir.isBlank()) continue;
            Path found = executable(Path.of(dir), "node");
            if (found != null) return found;
        }
        return null;
    }

    private static Path sibling(Path node, String name) {
        Path found = executable(node.getParent(), name);
        if (found != null) return found;
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            found = executable(Path.of(dir), name);
            if (found != null) return found;
        }
        return null;
    }

    private static Path executable(Path dir, String name) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String file : windows ? List.of(name + ".exe", name + ".cmd") : List.of(name)) {
            Path candidate = dir.resolve(file);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }
}
