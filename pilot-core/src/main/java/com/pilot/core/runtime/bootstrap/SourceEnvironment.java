package com.pilot.core.runtime.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 启动器与 IDE 共用的环境准备逻辑；仅依赖 JDK，也可直接作为多文件 Java 源码程序启动。 */
public final class SourceEnvironment {
    public record Mobile(Path libraries, Path javaHome, boolean ready, String message) {
    }

    private final Path root;
    private final Path cache;
    private final BootstrapPlatform platform;
    private final BootstrapPlatform bridgePlatform;
    private final RuntimeAssets assets;
    private final Consumer<String> progress;
    private final Map<String, String> environment;
    private final boolean offline;

    public SourceEnvironment(Path root, Path cache, boolean network, Consumer<String> progress) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.cache = cache.toAbsolutePath().normalize();
        this.platform = BootstrapPlatform.current();
        this.bridgePlatform = platform.bridgeCandidate(appleSiliconHost());
        this.progress = progress;
        this.offline = !network;
        this.environment = DependencyCaches.environment(this.root, System.getenv());
        this.assets = new RuntimeAssets(this.cache, RuntimeAssets.manifest(this.root), network, progress);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !List.of("launch", "prepare", "build").contains(args[0])) {
            throw new IllegalArgumentException("用法：SourceEnvironment.java launch|prepare|build 项目根目录 [后台参数]");
        }
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Path cache = cache(root);
        boolean network = !"true".equalsIgnoreCase(System.getenv("PILOT_OFFLINE"));
        var bootstrap = new SourceEnvironment(root, cache, network, message -> System.out.println("Pilot: " + message));
        Path node;
        try (var lock = bootstrap.lock()) {
            node = bootstrap.ensureNode();
        }
        if ("prepare".equals(args[0])) {
            return;
        }
        bootstrap.launch(node, Arrays.copyOfRange(args, 2, args.length), "launch".equals(args[0]));
    }

    public static Path cache(Path root) {
        String configured = System.getenv("PILOT_BOOTSTRAP_DIR");
        String data = System.getenv("PILOT_DATA_DIR");
        Path base = data == null || data.isBlank() ? root.resolve(".pilot") : Path.of(data);
        return configured == null || configured.isBlank() ? base.resolve("bootstrap")
            : Path.of(configured).toAbsolutePath().normalize();
    }

    public Mobile prepareMobile(Path libraries) throws Exception {
        try (var lock = lock()) {
            return prepareMobileUnlocked(libraries);
        }
    }

    private Mobile prepareMobileUnlocked(Path configured) throws Exception {
        Path libraries = configured != null ? configured.toAbsolutePath().normalize()
            : Files.isDirectory(root.resolve("mobile-bridge/lib")) ? root.resolve("mobile-bridge/lib")
                : root.resolve("pilot-mobile/bridge/build/install/bridge/lib");
        if (!Files.isRegularFile(libraries.resolve("bridge.jar"))) {
            return mobileResult(new Mobile(libraries, null, false, "手机连接组件尚未打包，请单独构建组件或使用完整安装包；电脑功能可正常使用"));
        }
        if (!bridgePlatform.mobileSupported()) {
            return mobileResult(new Mobile(null, null, false, "当前架构暂没有可用的手机连接原生库，电脑上的其他功能仍可使用：" + platform.key()));
        }
        Path javaHome =
            bridgePlatform.equals(platform) ? Path.of(System.getProperty("java.home")) : managedBridgeJava();
        progress.accept("正在验证手机连接原生库与二维码能力");
        int probe = probe(javaHome, libraries);
        if (probe != 0 && platform.windows()) {
            // 先用私有 Temurin 重试，通常可直接复用 JDK 随附的 VC 运行库，无需管理员安装。
            javaHome = managedBridgeJava();
            probe = probe(javaHome, libraries);
            if (probe == 12) {
                progress.accept("正在补齐微软 VC++ 运行库，系统可能请求管理员授权");
                installWindowsRuntime();
                probe = probe(javaHome, libraries);
            }
        }
        if (probe != 0) {
            return mobileResult(new Mobile(libraries, javaHome, false, "手机连接原生能力验证未通过，请查看环境检查状态；代码 " + probe));
        }
        return mobileResult(new Mobile(libraries, javaHome, true, "手机连接环境已就绪"));
    }

    private Mobile mobileResult(Mobile result) {
        progress.accept(result.message());
        return result;
    }

    private String projectKey() throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(root.toRealPath().toString().getBytes(StandardCharsets.UTF_8)))
            .substring(0, 16);
    }

    private int probe(Path home, Path libraries) throws Exception {
        return execute(
            List.of(java(home), "--enable-native-access=ALL-UNNAMED", "-cp", libraries + File.separator + "*",
                "com.pilot.mobile.bridge.MainKt", "--check-runtime"),
            root, childEnvironment(home, null), Duration.ofSeconds(45), false).code();
    }

    private void installWindowsRuntime() throws Exception {
        Path archive = assets.download(assets.asset("vcredist"));
        Path installer = cache.resolve("vc_redist.x64.exe");
        Files.copy(archive, installer, StandardCopyOption.REPLACE_EXISTING);
        Path script = cache.resolve("install-vcredist.ps1");
        try (var input = SourceEnvironment.class.getResourceAsStream("/bootstrap/install-vcredist.ps1")) {
            if (input != null) {
                Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(root.resolve("pilot-core/src/main/resources/bootstrap/install-vcredist.ps1"), script,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
        var result = execute(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            script.toString(), "-Installer", installer.toString()), root, environment, Duration.ofMinutes(8), true);
        if (!List.of(0, 3010, 1638).contains(result.code())) {
            throw new IOException("VC++ 自动安装未完成，可能需要管理员授权；手机连接暂不可用，其他功能可继续使用");
        }
    }

    private Path managedBridgeJava() throws Exception {
        var asset = assets.asset("jdk25." + bridgePlatform.key());
        boolean cached = Files.exists(cache.resolve(asset.home()).resolve(".pilot-verified"));
        Path home = javaHome(assets.install(asset));
        if (!usableBridgeJava(home) && cached) {
            home = javaHome(assets.install(asset, true));
        }
        if (!usableBridgeJava(home)) {
            throw new IOException("私有 Java 25 无法在当前系统运行，请检查系统版本与架构");
        }
        return home;
    }

    private boolean usableBridgeJava(Path home) {
        if (!Files.isRegularFile(home.resolve("bin").resolve(platform.executable("javac")))) {
            return false;
        }
        try {
            var result = execute(List.of(java(home), "-XshowSettings:properties", "-version"), root, environment,
                Duration.ofSeconds(8), false);
            var arch = Pattern.compile("os.arch\\s*=\\s*(\\S+)").matcher(result.output());
            return result.code() == 0
                && result.output().matches("(?s).*java.specification.version\\s*=\\s*(2[5-9]|[3-9][0-9])\\s.*")
                && arch.find()
                && BootstrapPlatform.detect(platform.os(), arch.group(1)).arch().equals(bridgePlatform.arch());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean appleSiliconHost() {
        if (!"mac".equals(platform.os()) || !"x64".equals(platform.arch())) {
            return false;
        }
        try {
            var result = execute(List.of("/usr/sbin/sysctl", "-n", "hw.optional.arm64"), root, System.getenv(),
                Duration.ofSeconds(5), false);
            return result.code() == 0 && result.output().trim().equals("1");
        } catch (Exception e) {
            return false;
        }
    }

    private Path ensureNode() throws Exception {
        for (Path candidate : pathExecutables(platform.executable("node"))) {
            if (usableNode(candidate)) {
                return candidate;
            }
        }
        var asset = assets.asset("node." + platform.key());
        boolean cached = Files.exists(cache.resolve(asset.home()).resolve(".pilot-verified"));
        Path home = assets.install(asset);
        Path executable = platform.windows() ? home.resolve("node.exe") : home.resolve("bin/node");
        if (!usableNode(executable) && cached) {
            home = assets.install(asset, true);
            executable = platform.windows() ? home.resolve("node.exe") : home.resolve("bin/node");
        }
        if (!usableNode(executable)) {
            throw new IOException("已下载的 Node 无法运行，请检查系统版本、架构及 Linux glibc 兼容性");
        }
        return executable;
    }

    private boolean usableNode(Path node) {
        try {
            Path npm = npmCli(node);
            if (npm == null) {
                return false;
            }
            var result =
                execute(List.of(node.toString(), "-p", "'PILOT_NODE_RUNTIME '+process.versions.node+' '+process.arch"),
                    root, environment, Duration.ofSeconds(8), false);
            return result.code() == 0 && nodeVersionMatches(result.output(), platform)
                && execute(List.of(node.toString(), npm.toString(), "--version"), root, environment,
                    Duration.ofSeconds(8), false).code() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean nodeVersionMatches(String output, BootstrapPlatform platform) {
        var match = Pattern.compile("(?m)^PILOT_NODE_RUNTIME (\\d+)\\.(\\d+)\\.\\d+ (\\S+)\\s*$").matcher(output);
        if (!match.find()) {
            return false;
        }
        int major = Integer.parseInt(match.group(1));
        int minor = Integer.parseInt(match.group(2));
        return (major > 22 || major == 22 && minor >= 12)
            && BootstrapPlatform.detect(platform.os(), match.group(3)).arch().equals(platform.arch());
    }

    private static Path npmCli(Path node) {
        List<Path> candidates = new ArrayList<>(List.of(node.getParent().resolve("node_modules/npm/bin/npm-cli.js"),
            node.getParent().resolve("../lib/node_modules/npm/bin/npm-cli.js").normalize(),
            Path.of("/usr/share/nodejs/npm/bin/npm-cli.js")));
        Path sibling = node.getParent().resolve("npm");
        if (Files.exists(sibling)) {
            try {
                Path resolved = sibling.toRealPath();
                if (resolved.getFileName().toString().equals("npm-cli.js")) {
                    candidates.addFirst(resolved);
                }
            } catch (IOException e) {
                // 不完整的系统 Node 会退回带 npm 的私有运行时。
            }
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private List<Path> pathExecutables(String executable) {
        String path = environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
            .map(Map.Entry::getValue).findFirst().orElse("");
        return Arrays.stream(path.split(Pattern.quote(File.pathSeparator))).filter(value -> !value.isBlank())
            .map(value -> Path.of(value).resolve(executable)).filter(Files::isRegularFile).toList();
    }

    private String java(Path home) {
        return home.resolve("bin").resolve(platform.executable("java")).toString();
    }

    private static Path javaHome(Path extracted) {
        return Files.isDirectory(extracted.resolve("Contents/Home")) ? extracted.resolve("Contents/Home") : extracted;
    }

    private void launch(Path node, String[] args, boolean startServer) throws Exception {
        Map<String, String> env = childEnvironment(Path.of(System.getProperty("java.home")), node);
        env.put("PILOT_BOOTSTRAP_NODE", node.toString());
        try (var lock =
            BootstrapLock.acquire(cache.resolve("build-" + projectKey() + ".lock"), Duration.ofMinutes(35))) {
            build(node, env);
        }
        if (!startServer) {
            return;
        }
        List<String> server = new ArrayList<>(
            List.of(java(Path.of(System.getProperty("java.home"))), "--enable-native-access=ALL-UNNAMED", "-jar",
                root.resolve("pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar").toString()));
        server.addAll(List.of(args));
        run(server, root, env, Duration.ofDays(365));
    }

    private void build(Path node, Map<String, String> env) throws Exception {
        Path frontend = root.resolve("pilot-web/frontend");
        if (Files.notExists(frontend.resolve("node_modules"))) {
            Path npm = npmCli(node);
            if (npm == null) {
                throw new IOException("Node 安装缺少 npm，请重新准备运行环境");
            }
            progress.accept("正在按锁文件补齐前端依赖");
            run(List.of(node.toString(), npm.toString(), "ci"), frontend, env, Duration.ofMinutes(15));
            Files.createDirectories(root.resolve(".pilot"));
            Files.writeString(root.resolve(".pilot/frontend-deps-ask.stamp"), Instant.now().toString());
        }
        run(List.of(node.toString(), root.resolve("tools/ask-frontend-deps.mjs").toString()), root, env,
            Duration.ofMinutes(10));
        Path maven = assets.install(assets.asset("maven"));
        Path boot;
        try (var jars = Files.list(maven.resolve("boot"))) {
            boot = jars.filter(path -> path.getFileName().toString().startsWith("plexus-classworlds-")).findFirst()
                .orElseThrow(() -> new IOException("Maven 安装包缺少启动组件"));
        }
        List<String> build = new ArrayList<>(List.of(java(Path.of(System.getProperty("java.home"))),
            "--enable-native-access=ALL-UNNAMED", "-Dmaven.home=" + maven, "-Dmaven.conf=" + maven.resolve("conf"),
            "-Dmaven.repo.local=" + env.get("PILOT_MAVEN_REPOSITORY"),
            "-Dclassworlds.conf=" + maven.resolve("bin/m2.conf"), "-Dmaven.multiModuleProjectDirectory=" + root,
            "-classpath", boot.toString(), "org.codehaus.plexus.classworlds.launcher.Launcher", "-B", "-ntp", "-pl",
            "pilot-server", "-am", "install"));
        if (offline) {
            build.add("-o");
        }
        run(build, root, env, Duration.ofMinutes(30));
    }

    private Map<String, String> childEnvironment(Path javaHome, Path node) {
        Map<String, String> env = new HashMap<>(environment);
        String key = env.keySet().stream().filter(name -> name.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String prefix = javaHome.resolve("bin") + File.pathSeparator;
        if (node != null) {
            prefix = node.getParent() + File.pathSeparator + prefix;
        }
        env.put(key, prefix + env.getOrDefault(key, ""));
        env.put("JAVA_HOME", javaHome.toString());
        env.put("PILOT_BOOTSTRAP_DIR", cache.toString());
        if (offline) {
            env.put("NPM_CONFIG_OFFLINE", "true");
            env.put("PILOT_RUNTIME_PROVISION", "false");
            env.put("PILOT_DESKTOP_PROVISION", "false");
            env.put("PILOT_BROWSER_PROVISION", "false");
            env.put("PILOT_BROWSER_DOWNLOAD_CHROMIUM", "false");
            env.put("PILOT_WEB_SEARCH_LOCAL", "false");
            env.putIfAbsent("PILOT_MOBILE_PROVISION", "true");
        }
        return env;
    }

    private BootstrapLock lock() throws Exception {
        progress.accept("正在检查本机依赖；若已有启动进程正在准备，将复用其结果");
        return BootstrapLock.acquire(cache.resolve("prepare.lock"), Duration.ofMinutes(20));
    }

    public record Execution(int code, String output) {
    }

    public static void run(List<String> command, Path directory, Map<String, String> env, Duration timeout)
        throws Exception {
        if (execute(command, directory, env, timeout, true).code() != 0) {
            throw new IOException("环境准备命令失败，请查看上方构建输出；未继续启动应用");
        }
    }

    public static Execution execute(List<String> command, Path directory, Map<String, String> env, Duration timeout,
        boolean inherit) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        if (inherit) {
            builder.inheritIO();
        }
        Process child = builder.start();
        if (!inherit) {
            child.getOutputStream().close();
        }
        Thread cleanup = new Thread(() -> {
            child.descendants().forEach(ProcessHandle::destroy);
            child.destroy();
        });
        Runtime.getRuntime().addShutdownHook(cleanup);
        var output = new ByteArrayOutputStream();
        Thread reader = inherit ? null : Thread.startVirtualThread(() -> {
            try (var input = child.getInputStream()) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    synchronized (output) {
                        if (output.size() + count <= 4 * 1024 * 1024) {
                            output.write(bytes, 0, count);
                        }
                    }
                }
            } catch (IOException e) {
                // 进程取消或超时会关闭管道。
            }
        });
        try {
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("环境准备命令超时");
            }
            if (reader != null) {
                reader.join(1000);
            }
            synchronized (output) {
                return new Execution(child.exitValue(), output.toString(StandardCharsets.UTF_8));
            }
        } finally {
            child.descendants().forEach(ProcessHandle::destroy);
            child.destroyForcibly();
            if (reader != null) {
                reader.interrupt();
            }
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException e) {
                // JVM 正在退出，由已经注册的钩子完成本进程的子进程清理。
            }
        }
    }
}
