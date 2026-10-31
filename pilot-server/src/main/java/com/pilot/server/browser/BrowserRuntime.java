package com.pilot.server.browser;

import com.pilot.core.browser.BrowserBackend;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器运行时的装配与生命周期。
 *
 * <p>启动时在后台线程里依次完成：释放 sidecar 脚本 → 用固定版本的 {@code playwright-core}
 * 安装依赖（首次）→ 与 sidecar 握手并真的启动一次浏览器。只有握手成功，{@link #availability()}
 * 才返回可用，{@code browser} 工具才会向模型声明；失败时原因会保留下来原样告知模型。
 *
 * <p>浏览器本身优先用本机的 Chrome / Edge；都没有时（且允许下载）再安装 Playwright 自带的 Chromium。
 * 运行时目录放在仓库的 {@code .pilot/browser}（与内置 Node/Python 同处，不进 git），找不到仓库根则用数据目录。
 */
@Component
public class BrowserRuntime implements BrowserBackend, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BrowserRuntime.class);
    /** 固定版本：sidecar 协议与快照脚本只针对这个版本验证过。 */
    static final String PLAYWRIGHT_VERSION = "1.64.1";
    private static final String SCRIPT_RESOURCE = "browser/browser-sidecar.mjs";
    private static final String SCRIPT_NAME = "browser-sidecar.mjs";

    private final PilotDataDir dataDir;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean provision;
    private final boolean downloadChromium;

    private volatile PlaywrightBackend backend;
    private volatile String pendingReason = "浏览器运行时尚未启动";
    private volatile boolean running;
    private volatile Thread worker;

    public BrowserRuntime(PilotDataDir dataDir, ObjectMapper mapper,
                          @Value("${pilot.browser.enabled:true}") boolean enabled,
                          @Value("${pilot.browser.provision:true}") boolean provision,
                          @Value("${pilot.browser.download-chromium:true}") boolean downloadChromium) {
        this.dataDir = dataDir;
        this.mapper = mapper;
        this.enabled = enabled;
        this.provision = provision;
        this.downloadChromium = downloadChromium;
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
        PlaywrightBackend current = backend;
        if (current != null) current.closeSession(sessionId);
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
            PlaywrightBackend candidate = new PlaywrightBackend(node, script, home, mapper);
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
            if (run(home, node, Duration.ofMinutes(5), command) == 0
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
        log.info("本机没有 Chrome / Edge，正在下载 Playwright Chromium（约 150MB，仅此一次）");
        return run(home, node, Duration.ofMinutes(15), List.of(node.toString(), cli.toString(), "install", "chromium")) == 0;
    }

    private int run(Path home, Path node, Duration timeout, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        String path = builder.environment().getOrDefault("PATH", "");
        builder.environment().put("PATH", node.getParent() + File.pathSeparator + path);
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
