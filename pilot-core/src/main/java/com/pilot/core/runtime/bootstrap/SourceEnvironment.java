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
import java.util.Properties;
import java.util.UUID;
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
    private Path verifiedBridgeJava;
    private final String reportName = "mobile-" + UUID.randomUUID() + ".properties";

    public SourceEnvironment(Path root, Path cache, boolean network, Consumer<String> progress) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.cache = cache.toAbsolutePath().normalize();
        this.platform = BootstrapPlatform.current();
        this.bridgePlatform = platform.bridgeCandidate(appleSiliconHost());
        this.progress = progress;
        this.offline = !network;
        this.environment = new HashMap<>(System.getenv());
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
            try {
                String configured = option(args, "--pilot.mobile.libraries");
                boolean mobileEnabled = mobileProvisionEnabled(args, System.getenv());
                bootstrap.environment.put("PILOT_MOBILE_PROVISION", Boolean.toString(mobileEnabled));
                if (mobileEnabled) {
                    bootstrap
                        .prepareMobileUnlocked(configured == null || configured.isBlank() ? null : Path.of(configured));
                }
            } catch (Exception e) {
                bootstrap.mobileResult(new Mobile(null, null, false, "手机连接环境准备未完成，请在连接手机窗口检查并修复；其他功能可继续使用"));
            }
        }
        bootstrap.environment.put("PILOT_BOOTSTRAP_PREPARED", "true");
        if ("prepare".equals(args[0])) {
            return;
        }
        bootstrap.launch(node, Arrays.copyOfRange(args, 2, args.length), "launch".equals(args[0]));
    }

    private static String option(String[] args, String name) {
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith(name + "=")) {
                return args[i].substring(name.length() + 1);
            }
            if (args[i].equals(name) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return null;
    }

    static boolean mobileProvisionEnabled(String[] args, Map<String, String> env) {
        String mobile = option(args, "--pilot.mobile.provision");
        if (mobile == null) {
            mobile = env.get("PILOT_MOBILE_PROVISION");
        }
        String runtime = option(args, "--pilot.runtime.provision");
        if (runtime == null) {
            runtime = env.getOrDefault("PILOT_RUNTIME_PROVISION", "true");
        }
        return !"false".equalsIgnoreCase(mobile == null ? runtime : mobile);
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
        if (!bridgePlatform.mobileSupported()) {
            return mobileResult(new Mobile(null, null, false, "当前架构暂没有可用的手机连接原生库，电脑上的其他功能仍可使用：" + platform.key()));
        }
        Path libraries = configured != null ? configured.toAbsolutePath().normalize()
            : Files.isDirectory(root.resolve("mobile-bridge/lib")) ? root.resolve("mobile-bridge/lib")
                : root.resolve("pilot-mobile/bridge/build/install/bridge/lib");
        Path javaHome = bridgePlatform.equals(platform) ? Path.of(System.getProperty("java.home")) : managedJava21();
        Path source = root.resolve("pilot-mobile/bridge/src");
        if (Files.isDirectory(source) && configured == null) {
            javaHome = ensureJava21();
            String fingerprint = fingerprint();
            Path stamp = cache.resolve("bridge-source-" + projectKey() + ".sha256");
            if (!Files.isRegularFile(libraries.resolve("bridge.jar")) || !Files.isRegularFile(stamp)
                || !Files.readString(stamp).equals(fingerprint)) {
                progress.accept("正在补齐手机连接组件，不需要 Android SDK");
                List<String> command = new ArrayList<>(List.of(java(javaHome), "-classpath",
                    root.resolve("pilot-mobile/gradle/wrapper/gradle-wrapper.jar").toString(),
                    "org.gradle.wrapper.GradleWrapperMain", "-Ppilot.desktopOnly=true",
                    "-Dorg.gradle.java.installations.paths=" + javaHome, ":bridge:installDist"));
                if (offline) {
                    command.add("--offline");
                }
                Map<String, String> env = childEnvironment(javaHome, null);
                run(command, root.resolve("pilot-mobile"), env, Duration.ofMinutes(15));
                Files.writeString(stamp, fingerprint);
            }
        }
        if (!Files.isRegularFile(libraries.resolve("bridge.jar"))) {
            return mobileResult(new Mobile(libraries, javaHome, false, "手机连接组件缺失且当前目录没有可构建的源码，请使用完整安装包"));
        }
        progress.accept("正在验证手机连接原生库与二维码能力");
        int probe = probe(javaHome, libraries);
        if (probe != 0 && platform.windows()) {
            // 先用私有 Temurin 重试，通常可直接复用 JDK 随附的 VC 运行库，无需管理员安装。
            javaHome = managedJava21();
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

    private Mobile mobileResult(Mobile result) throws IOException {
        if (result.ready()) {
            verifiedBridgeJava = result.javaHome();
        }
        progress.accept(result.message());
        Properties report = new Properties();
        report.setProperty("platform", platform.key());
        report.setProperty("ready", Boolean.toString(result.ready()));
        report.setProperty("message", result.message());
        if (result.javaHome() != null) {
            report.setProperty("javaHome", result.javaHome().toString());
        }
        if (result.libraries() != null) {
            report.setProperty("libraries", result.libraries().toString());
        }
        Files.createDirectories(cache);
        try (var output = Files.newOutputStream(cache.resolve(reportName))) {
            report.store(output, "Pilot mobile environment");
        }
        return result;
    }

    public static Mobile preparedMobile(Path cache) throws IOException {
        String reportName = System.getenv("PILOT_BOOTSTRAP_REPORT");
        if (reportName == null || !reportName.matches("mobile-[a-f0-9-]{36}\\.properties")) {
            throw new IOException("本次启动没有完整的环境报告");
        }
        Properties report = new Properties();
        try (var input = Files.newInputStream(cache.resolve(reportName))) {
            report.load(input);
        }
        String libraries = report.getProperty("libraries");
        String home = report.getProperty("javaHome");
        return new Mobile(libraries == null ? null : Path.of(libraries), home == null ? null : Path.of(home),
            Boolean.parseBoolean(report.getProperty("ready")), report.getProperty("message", "环境尚未准备"));
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

    private Path ensureJava21() throws Exception {
        if (!bridgePlatform.equals(platform)) {
            return managedJava21();
        }
        for (Path home : javaCandidates()) {
            if (usableBridgeJava(home)) {
                return home;
            }
        }
        return managedJava21();
    }

    private Path managedJava21() throws Exception {
        var asset = assets.asset("jdk21." + bridgePlatform.key());
        boolean cached = Files.exists(cache.resolve(asset.home()).resolve(".pilot-verified"));
        Path home = javaHome(assets.install(asset));
        if (!usableBridgeJava(home) && cached) {
            home = javaHome(assets.install(asset, true));
        }
        if (!usableBridgeJava(home)) {
            throw new IOException("私有 Java 21 无法在当前系统运行，请检查系统版本与架构");
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
            return result.code() == 0 && result.output().matches("(?s).*java.specification.version\\s*=\\s*21\\s.*")
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

    private List<Path> javaCandidates() throws IOException {
        List<Path> homes = new ArrayList<>();
        for (String key : List.of("JAVA21_HOME", "JDK21_HOME", "JAVA_HOME")) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) {
                homes.add(Path.of(value));
            }
        }
        homes.add(Path.of(System.getProperty("java.home")));
        Path user = Path.of(System.getProperty("user.home"));
        List<Path> parents = new ArrayList<>(List.of(user.resolve("Library/Java/JavaVirtualMachines"),
            Path.of("/Library/Java/JavaVirtualMachines"), Path.of("/usr/lib/jvm")));
        String programFiles = environment.get("ProgramFiles");
        if (programFiles != null) {
            for (String name : List.of("Java", "Eclipse Adoptium", "Microsoft")) {
                parents.add(Path.of(programFiles, name));
            }
        }
        for (Path parent : parents) {
            if (Files.isDirectory(parent)) {
                try (var children = Files.list(parent)) {
                    children.limit(128).forEach(path -> {
                        homes.add(path);
                        homes.add(path.resolve("Contents/Home"));
                    });
                }
            }
        }
        return homes;
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

    private String fingerprint() throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String relative : List.of("pilot-mobile/bridge/src", "pilot-mobile/transport/src",
            "pilot-mobile/build.gradle.kts", "pilot-mobile/settings.gradle.kts", "pilot-mobile/bridge/build.gradle.kts",
            "pilot-mobile/transport/build.gradle.kts", "pilot-mobile/gradle/wrapper/gradle-wrapper.properties")) {
            try (var paths = Files.walk(root.resolve(relative))) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    hash.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                    hash.update(Files.readAllBytes(file));
                }
            }
        }
        return HexFormat.of().formatHex(hash.digest());
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
            "-Dclassworlds.conf=" + maven.resolve("bin/m2.conf"), "-Dmaven.multiModuleProjectDirectory=" + root,
            "-classpath", boot.toString(), "org.codehaus.plexus.classworlds.launcher.Launcher", "-pl", "pilot-server",
            "-am", "install"));
        if (offline) {
            build.add("-o");
        }
        run(build, root, env, Duration.ofMinutes(30));
    }

    private Map<String, String> childEnvironment(Path javaHome, Path node) {
        Map<String, String> env = new HashMap<>(environment);
        String key = env.keySet().stream().filter(name -> name.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String prefix = javaHome.resolve("bin") + File.pathSeparator;
        if (verifiedBridgeJava != null && bridgePlatform.equals(platform)) {
            prefix += verifiedBridgeJava.resolve("bin") + File.pathSeparator;
        }
        if (node != null) {
            prefix = node.getParent() + File.pathSeparator + prefix;
        }
        env.put(key, prefix + env.getOrDefault(key, ""));
        env.put("JAVA_HOME", javaHome.toString());
        env.put("PILOT_BOOTSTRAP_DIR", cache.toString());
        env.put("PILOT_BOOTSTRAP_REPORT", reportName);
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
