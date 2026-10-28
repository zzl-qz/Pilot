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
    private static final String WINDOWS_SHIM_RESOURCE = "searxng/pwd.py";
    private static final String ARCHIVE_NAME = "searxng.tar.gz";
    /** 小于这个大小的压缩包基本是下载中断留下的残骸。 */
    private static final long MIN_ARCHIVE_BYTES = 1_000_000L;
    private static final List<String> PYTHON_CANDIDATES = List.of(
            "/opt/homebrew/opt/python@3.13/bin/python3.13",
            "/opt/homebrew/opt/python@3.12/bin/python3.12",
            "/usr/local/opt/python@3.12/bin/python3.12",
            "python3.13",
            "python3.12",
            "python3.11");
    /** macOS/Linux 上 python3 常见是指向 3.x 的软链，Windows 的同名命令会拉起商店，所以不放进去。 */
    private static final List<String> POSIX_PYTHON_CANDIDATES = List.of("python3", "python");

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
            if (Files.isRegularFile(source.resolve("searx").resolve("webapp.py"))) {
                log.info("已有 SearXNG 源码，直接使用：{}", source);
            } else {
                log.info("本机还没有 SearXNG 源码，开始拉取");
                if (!clone(source)) {
                    return;
                }
            }
            Path venv = source.resolve(".venv");
            if (ready(venv)) {
                log.info("依赖已装好，直接启动 SearXNG");
            } else if (!install(source, venv)) {
                return;
            }
            ensureWindowsShim(venv);
            syncThemeStatic(source);
            ProcessBuilder builder = new ProcessBuilder(launchCommand(venv));
            builder.directory(source.toFile());
            builder.environment().put("SEARXNG_SETTINGS_PATH", settings.toAbsolutePath().toString());
            builder.environment().put("SEARXNG_BIND_ADDRESS", "127.0.0.1");
            builder.environment().put("SEARXNG_PORT", Integer.toString(port));
            Path logFile = root.resolve("searxng.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.redirectErrorStream(true);
            process = builder.start();
            if (!waitUntilUp(port, Duration.ofSeconds(60)) || !process.isAlive()) {
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
        if (isWindows()) {
            // 仓库里有 "...conf:socket" 这种 NTFS 不认的文件名，git checkout 必然失败，别浪费时间
            log.info("Windows 上跳过 git clone（SearXNG 仓库含非法文件名），直接用压缩包");
        } else if (gitClone(source)) {
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

    /**
     * 先克隆到同级临时目录再挪过去。直接 clone 到 source 时，网络中断会留下半截目录，
     * 而 Windows 上 git 子进程可能还占着 .git，删不干净，下次启动就会一直撞在“目录已存在”。
     */
    private boolean gitClone(Path source) {
        Path temp = source.getParent().resolve("clone");
        deleteTree(temp);
        int code = run(Path.of(System.getProperty("user.dir", ".")), Duration.ofMinutes(10),
                "git", "-c", "http.version=HTTP/1.1", "clone", "--depth", "1",
                "https://github.com/searxng/searxng.git", temp.toString());
        boolean ok = code == 0 && Files.isRegularFile(temp.resolve("searx").resolve("webapp.py"));
        if (ok) {
            ok = moveInto(temp, source);
        }
        deleteTree(temp);
        return ok;
    }

    private boolean downloadArchive(String url, Path source) {
        Path parent = source.getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            return false;
        }
        Path unpack = parent.resolve("unpack");
        Path extracted = unpack.resolve("searxng-master");
        // 上次已经解压好但没搬过去的源码，网络不顺时直接复用，省一次下载
        if (Files.isRegularFile(extracted.resolve("searx").resolve("webapp.py"))) {
            log.info("复用上次已解压的源码 {}", extracted);
            if (moveInto(extracted, source)) {
                deleteTree(unpack);
                return true;
            }
        }
        Path tar = parent.resolve(ARCHIVE_NAME);
        if (usableArchive(tar)) {
            log.info("复用已下载的压缩包 {}", tar);
        } else {
            int code = run(parent, Duration.ofMinutes(10),
                    "curl", "-fL", "--retry", "2", "--connect-timeout", "20", "--max-time", "300",
                    "-o", tar.toString(), url);
            if (code != 0 || !usableArchive(tar)) {
                // 半截文件留着只会让下一次误以为是下载好了
                try {
                    Files.deleteIfExists(tar);
                } catch (IOException ignored) {
                    // 删不掉就算了，下次按大小校验也会重新下载
                }
                return false;
            }
        }
        deleteTree(unpack);
        try {
            Files.createDirectories(unpack);
        } catch (IOException e) {
            return false;
        }
        // 仓库里 utils/templates 下的 httpd/nginx 站点模板含 "xxx:socket"，NTFS 建不出来，
        // 而且这些模板跟运行无关，直接跳过
        int code = run(parent, Duration.ofMinutes(2), "tar", "-xzf", tar.toString(), "-C", unpack.toString(),
                "--exclude=*sites-available*", "--exclude=*sites-enabled*");
        // 少数文件解不出来不影响运行，只要 webapp.py 在就继续
        if (!Files.isRegularFile(extracted.resolve("searx").resolve("webapp.py"))) {
            log.warn("解压 SearXNG 源码失败（tar 退出码 {}）", code);
            return false;
        }
        if (!moveInto(extracted, source)) {
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

    /** 下载中途断掉会留下 0 字节或半截文件，按大小和 gzip 头判断能不能直接用。 */
    private static boolean usableArchive(Path tar) {
        try {
            if (!Files.isRegularFile(tar) || Files.size(tar) < MIN_ARCHIVE_BYTES) {
                return false;
            }
            byte[] head = new byte[2];
            try (InputStream in = Files.newInputStream(tar)) {
                if (in.read(head) != 2) {
                    return false;
                }
            }
            return (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
        } catch (IOException e) {
            return false;
        }
    }

    /** 把下载好的源码放到 source。目录被占用导致 move 失败时退化成逐文件复制。 */
    private boolean moveInto(Path from, Path to) {
        deleteTree(to);
        try {
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            Files.move(from, to);
            return true;
        } catch (IOException e) {
            log.info("移动到 {} 失败（{}），改为复制", to, e.toString());
        }
        return copyTree(from, to);
    }

    private static boolean copyTree(Path from, Path to) {
        return copyDir(from, to) && Files.isRegularFile(to.resolve("searx").resolve("webapp.py"));
    }

    private static boolean copyDir(Path from, Path to) {
        try (var walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        } catch (IOException e) {
            log.warn("复制 {} 失败：{}", from, e.toString());
            return false;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        // 刚被杀掉的 git 子进程可能还占着 .git，等一会儿重试几次
        for (int attempt = 0; attempt < 3; attempt++) {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        try {
                            path.toFile().setWritable(true);
                            Files.deleteIfExists(path);
                        } catch (IOException | RuntimeException ignored) {
                            // 这一轮删不掉，下一轮再试
                        }
                    }
                });
            } catch (IOException ignored) {
                // 目录不存在或正在被占用
            }
            if (Files.notExists(root)) {
                return;
            }
            try {
                Thread.sleep(800L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("清理 {} 失败，目录可能仍被占用", root);
    }

    private boolean install(Path source, Path venv) {
        if (!Files.isRegularFile(venvPython(venv))) {
            String python = findPython();
            if (python == null) {
                log.warn("没有找到 Python 3.11 或更高版本，本机搜索起不来");
                return false;
            }
            log.info("第一次使用，正在用 {} 创建虚拟环境，随后安装依赖，可能要几分钟", python);
            if (run(source, Duration.ofMinutes(2), python, "-m", "venv", venv.toString()) != 0) {
                log.warn("创建虚拟环境失败");
                return false;
            }
        } else {
            log.info("虚拟环境还在，只补装缺的依赖");
        }
        String pip = venvPython(venv).toString();
        // Python 3.12 的 venv 不再自带 setuptools，缺了它 --no-build-isolation 直接报错
        if (run(source, Duration.ofMinutes(5), pip, "-m", "pip", "install", "--upgrade", "setuptools", "wheel") != 0) {
            log.warn("安装 setuptools/wheel 失败");
            return false;
        }
        if (run(source, Duration.ofMinutes(30), pip, "-m", "pip", "install", "-r", "requirements.txt") != 0) {
            log.warn("安装 SearXNG 依赖失败");
            return false;
        }
        if (run(source, Duration.ofMinutes(10), pip, "-m", "pip", "install", "--use-pep517", "--no-build-isolation", "-e", ".") != 0
                // 构建隔离模式下 pip 会自己拉构建依赖，再给一次机会
                && run(source, Duration.ofMinutes(10), pip, "-m", "pip", "install", "-e", ".") != 0) {
            log.warn("安装 SearXNG 失败");
            return false;
        }
        if (run(source, Duration.ofSeconds(60), pip, "-c", "import searx") != 0) {
            log.warn("SearXNG 装上了但导入不了，等下次启动重装");
            return false;
        }
        markReady(venv);
        return true;
    }

    /**
     * Windows 上 searx/webutils.py 用 os.sep 拼静态文件列表（"themes\\simple\\x.css"），
     * 而模板传的是 "themes/simple/x.css"，匹配不上就退回 /static/x.css，结果全是 404、页面没样式。
     * 把主题产物再放一份到 static 根目录，正好接住这条回退路径。
     */
    private void syncThemeStatic(Path source) {
        if (!isWindows()) {
            return;
        }
        Path staticRoot = source.resolve("searx").resolve("static");
        Path theme = staticRoot.resolve("themes").resolve("simple");
        if (!Files.isDirectory(theme) || sameContent(theme.resolve("manifest.json"), staticRoot.resolve("manifest.json"))) {
            return;
        }
        try (var children = Files.list(theme)) {
            for (Path child : children.toList()) {
                Path target = staticRoot.resolve(child.getFileName().toString());
                deleteTree(target);
                if (Files.isDirectory(child)) {
                    copyDir(child, target);
                } else {
                    Files.copy(child, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("已把主题静态资源同步到 {}", staticRoot);
        } catch (IOException e) {
            log.warn("同步主题静态资源失败：{}", e.toString());
        }
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.isRegularFile(a) && Files.isRegularFile(b)
                    && Files.size(a) == Files.size(b)
                    && java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Windows 上没有 pwd 模块，而 searx/valkeydb.py 顶层就 import 它，不补一个整个服务都导入不了。
     * 放在 site-packages 里，pip 升级依赖也不会清掉。
     */
    private void ensureWindowsShim(Path venv) {
        if (!isWindows()) {
            return;
        }
        Path sitePackages = venv.resolve("Lib").resolve("site-packages");
        if (!Files.isDirectory(sitePackages)) {
            return;
        }
        Path target = sitePackages.resolve("pwd.py");
        try (InputStream in = new ClassPathResource(WINDOWS_SHIM_RESOURCE).getInputStream()) {
            byte[] content = in.readAllBytes();
            if (Files.isRegularFile(target) && Files.size(target) == content.length) {
                return;
            }
            Files.copy(new java.io.ByteArrayInputStream(content), target, StandardCopyOption.REPLACE_EXISTING);
            log.info("已写入 Windows 兼容模块 {}", target);
        } catch (IOException e) {
            log.warn("写入 Windows 兼容模块失败：{}", e.toString());
        }
    }

    /** 装好一次就留个标记，下次启动不再重复跑 pip。 */
    private static void markReady(Path venv) {
        try {
            Files.writeString(marker(venv), "ok");
        } catch (IOException e) {
            log.warn("写安装标记失败：{}", e.toString());
        }
    }

    private static Path marker(Path venv) {
        return venv.resolve(".pilot-ready");
    }

    private static boolean ready(Path venv) {
        return Files.isRegularFile(venvPython(venv)) && Files.isRegularFile(marker(venv));
    }

    /** 优先用 pip 生成的入口脚本；没生成时退回模块入口，避免白装一遍。 */
    private static List<String> launchCommand(Path venv) {
        Path script = runner(venv);
        if (Files.isRegularFile(script)) {
            return List.of(script.toString());
        }
        return List.of(venvPython(venv).toString(), "-m", "searx.webapp");
    }

    private String findPython() {
        List<String> candidates = new ArrayList<>();
        String runtime = System.getProperty("pilot.runtime.bin");
        if (runtime != null) {
            String file = isWindows() ? "python.exe" : "python3";
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
        if (!isWindows()) {
            candidates.addAll(POSIX_PYTHON_CANDIDATES);
        }
        for (String candidate : candidates) {
            if (supported(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
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
        // 先记下子孙进程，父进程一退它们就成孤儿了，Windows 上还会占着源码目录的文件
        List<ProcessHandle> children = current.descendants().toList();
        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
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
