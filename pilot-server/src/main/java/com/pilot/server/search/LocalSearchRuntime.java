package com.pilot.server.search;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.config.WebSearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pilot 启动时在本机拉起 SearXNG。
 * 不使用 Docker。源码和虚拟环境放在数据目录，打包时也可以改指到安装目录里的副本。
 */
@Component
public class LocalSearchRuntime implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LocalSearchRuntime.class);
    private static final String SETTINGS_RESOURCE = "searxng/settings.yml";
    private static final List<String> PYTHON_CANDIDATES = List.of(
            "/opt/homebrew/opt/python@3.13/bin/python3.13",
            "/opt/homebrew/opt/python@3.12/bin/python3.12",
            "/usr/local/opt/python@3.12/bin/python3.12",
            "python3.13",
            "python3.12",
            "python3.11");

    private final WebSearchProperties properties;
    private final PilotDataDir dataDir;
    private volatile Process process;
    private volatile boolean running;

    public LocalSearchRuntime(WebSearchProperties properties, PilotDataDir dataDir) {
        this.properties = properties;
        this.dataDir = dataDir;
    }

    @Override
    public void start() {
        if (!properties.local() || !properties.loopback()) {
            return;
        }
        int port = properties.port();
        if (portOpen(port)) {
            log.info("本机 {} 已有搜索服务，不再重复启动", port);
            running = true;
            return;
        }
        try {
            Path root = managedRoot();
            Files.createDirectories(root);
            Path settings = root.resolve("settings.yml");
            if (Files.notExists(settings)) {
                copySettings(settings);
            }
            Path source = sourceDir();
            if (!Files.isRegularFile(source.resolve("searx").resolve("webapp.py"))) {
                if (!clone(source)) {
                    return;
                }
            }
            Path venv = source.resolve(".venv");
            if (!Files.isRegularFile(runner(venv))) {
                if (!install(source, venv)) {
                    return;
                }
            }
            ProcessBuilder builder = new ProcessBuilder(runner(venv).toString());
            builder.directory(source.toFile());
            builder.environment().put("SEARXNG_SETTINGS_PATH", settings.toAbsolutePath().toString());
            builder.environment().put("SEARXNG_BIND_ADDRESS", "127.0.0.1");
            builder.environment().put("SEARXNG_PORT", Integer.toString(port));
            Path logFile = root.resolve("searxng.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.redirectErrorStream(true);
            process = builder.start();
            if (!waitUntilUp(port, Duration.ofSeconds(45)) || !process.isAlive()) {
                log.warn("SearXNG 没有在 {} 起来，日志：{}", port, logFile);
                stopProcess();
                return;
            }
            running = true;
            log.info("本机搜索已启动：http://127.0.0.1:{}/search", port);
        } catch (IOException e) {
            log.warn("启动本机搜索失败：{}", e.toString());
        }
    }

    @Override
    public void stop() {
        stopProcess();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 比内置 Python/Node 晚一步，好让 SearXNG 用上刚准备好的解释器
        return Integer.MIN_VALUE + 1;
    }

    private Path sourceDir() {
        if (!properties.home().isBlank()) {
            return Path.of(properties.home());
        }
        Path bundled = Path.of(System.getProperty("user.dir", "."), "runtime", "searxng");
        if (Files.isRegularFile(bundled.resolve("searx").resolve("webapp.py"))) {
            return bundled;
        }
        return managedRoot().resolve("src");
    }

    /** 不管 IDE 的工作目录是仓库根还是 pilot-server，都把 SearXNG 放在仓库下的 .pilot。 */
    private Path managedRoot() {
        Path repo = repoRoot();
        if (repo != null) {
            return repo.resolve(".pilot").resolve("searxng");
        }
        return dataDir.file("searxng");
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isRegularFile(dir.resolve("pilot-server").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static void copySettings(Path target) throws IOException {
        try (InputStream in = new ClassPathResource(SETTINGS_RESOURCE).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean clone(Path source) {
        log.info("第一次使用，正在下载 SearXNG 到 {}", source);
        if (source.getParent() != null) {
            try {
                Files.createDirectories(source.getParent());
            } catch (IOException e) {
                log.warn("创建搜索目录失败：{}", e.toString());
                return false;
            }
        }
        deleteTree(source);
        if (gitClone(source)) {
            return true;
        }
        deleteTree(source);
        for (String url : List.of(
                "https://github.com/searxng/searxng/archive/refs/heads/master.tar.gz",
                "https://ghfast.top/https://github.com/searxng/searxng/archive/refs/heads/master.tar.gz")) {
            log.info("改从压缩包下载：{}", url);
            if (downloadArchive(url, source)) {
                return true;
            }
            deleteTree(source);
        }
        log.warn("下载 SearXNG 失败。网络能访问 GitHub 后再重启，或把源码放到 runtime/searxng");
        return false;
    }

    private boolean gitClone(Path source) {
        int code = run(Path.of(System.getProperty("user.dir", ".")), Duration.ofMinutes(3),
                "git", "-c", "http.version=HTTP/1.1", "clone", "--depth", "1",
                "https://github.com/searxng/searxng.git", source.toString());
        return code == 0 && Files.isRegularFile(source.resolve("searx").resolve("webapp.py"));
    }

    private boolean downloadArchive(String url, Path source) {
        Path parent = source.getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            return false;
        }
        Path tar = parent.resolve("searxng.tar.gz");
        int code = run(parent, Duration.ofMinutes(5),
                "curl", "-fL", "--retry", "2", "--max-time", "180", "-o", tar.toString(), url);
        if (code != 0) {
            return false;
        }
        Path unpack = parent.resolve("unpack");
        deleteTree(unpack);
        try {
            Files.createDirectories(unpack);
        } catch (IOException e) {
            return false;
        }
        if (run(parent, Duration.ofMinutes(2), "tar", "-xzf", tar.toString(), "-C", unpack.toString()) != 0) {
            return false;
        }
        Path extracted = unpack.resolve("searxng-master");
        if (!Files.isRegularFile(extracted.resolve("searx").resolve("webapp.py"))) {
            return false;
        }
        try {
            Files.move(extracted, source);
        } catch (IOException e) {
            return false;
        }
        deleteTree(unpack);
        try {
            Files.deleteIfExists(tar);
        } catch (IOException ignored) {
            // 压缩包留着也不影响运行
        }
        return true;
    }

    private static void deleteTree(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 删不掉就留给下一次启动再试
                }
            });
        } catch (IOException ignored) {
            // 目录不存在或正在被占用
        }
    }

    private boolean install(Path source, Path venv) {
        String python = findPython();
        if (python == null) {
            log.warn("没有找到 Python 3.11 或更高版本，本机搜索起不来");
            return false;
        }
        log.info("第一次使用，正在用 {} 安装 SearXNG，可能要几分钟", python);
        if (run(source, Duration.ofMinutes(2), python, "-m", "venv", venv.toString()) != 0) {
            log.warn("创建虚拟环境失败");
            return false;
        }
        String pip = venvPython(venv).toString();
        if (run(source, Duration.ofMinutes(20), pip, "-m", "pip", "install", "-r", "requirements.txt") != 0) {
            log.warn("安装 SearXNG 依赖失败");
            return false;
        }
        if (run(source, Duration.ofMinutes(10), pip, "-m", "pip", "install", "--use-pep517", "--no-build-isolation", "-e", ".") != 0) {
            log.warn("安装 SearXNG 失败");
            return false;
        }
        return Files.isRegularFile(runner(venv));
    }

    private String findPython() {
        List<String> candidates = new ArrayList<>();
        String runtime = System.getProperty("pilot.runtime.bin");
        if (runtime != null) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            String file = windows ? "python.exe" : "python3";
            for (String dir : runtime.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve(file);
                if (Files.isRegularFile(candidate)) {
                    candidates.add(candidate.toString());
                }
            }
        }
        if (!properties.python().isBlank()) {
            candidates.add(properties.python());
        }
        candidates.addAll(PYTHON_CANDIDATES);
        for (String candidate : candidates) {
            if (supported(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean supported(String python) {
        return run(Path.of(System.getProperty("user.dir", ".")), Duration.ofSeconds(15),
                python, "-c", "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)") == 0;
    }

    private static int run(Path directory, Duration timeout, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("命令超时：{}", String.join(" ", command));
                return -1;
            }
            int code = process.exitValue();
            if (code != 0) {
                String text = new String(output, java.nio.charset.StandardCharsets.UTF_8).strip();
                if (text.length() > 1200) {
                    text = text.substring(text.length() - 1200);
                }
                log.warn("命令退出 {}：{}{}", code, String.join(" ", command), text.isEmpty() ? "" : "\n" + text);
            }
            return code;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void stopProcess() {
        Process current = process;
        process = null;
        if (current == null) {
            return;
        }
        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private static boolean waitUntilUp(int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (portOpen(port)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return portOpen(port);
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path venvPython(Path venv) {
        Path windows = venv.resolve("Scripts").resolve("python.exe");
        if (Files.isRegularFile(windows)) {
            return windows;
        }
        return venv.resolve("bin").resolve("python3");
    }

    private static Path runner(Path venv) {
        Path windows = venv.resolve("Scripts").resolve("searxng-run.exe");
        if (Files.isRegularFile(windows)) {
            return windows;
        }
        return venv.resolve("bin").resolve("searxng-run");
    }
}
