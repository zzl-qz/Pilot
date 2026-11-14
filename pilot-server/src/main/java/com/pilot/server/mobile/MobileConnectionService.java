package com.pilot.server.mobile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.pilot.core.runtime.bootstrap.BootstrapPlatform;
import com.pilot.core.runtime.bootstrap.MobileBridgeLayout;
import com.pilot.core.util.HostOs;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.maintenance.DataMaintenance;
import com.pilot.server.runtime.SourceLayout;
import com.pilot.server.security.SecurityProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** 连接桥随后台服务存活，Web 与桌面共用设备身份和授权。管理令牌不传给前端。 */
@Service
public class MobileConnectionService {
    private static final Logger log = LoggerFactory.getLogger(MobileConnectionService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 调连接桥管理接口的上限。 */
    private static final Duration ADMIN_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final Path data;
    private final DataMaintenance maintenance;
    private final String configuredLibraries;
    private final String relay;
    private final SecurityProperties security;
    private final ObjectMapper mapper;
    private final HttpClient http =
        HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(CONNECT_TIMEOUT).build();
    private final ScheduledExecutorService supervisor = Executors
        .newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("pilot-mobile-supervisor").factory());
    private Process process;
    private URI admin;
    private String token;
    private volatile int port;
    private boolean watching;
    private boolean closed;
    private long retryAfter;
    private int failures;
    private MobileEnvironment environment;
    private String connectionMode = "auto";
    private String lastStatusDiagnostic = "";

    @Autowired
    public void configureConnectionMode(@Value("${pilot.mobile.mode:auto}") String value) {
        if (!List.of("auto", "direct").contains(value)) {
            throw new IllegalArgumentException("pilot.mobile.mode 只能配置 auto 或 direct");
        }
        connectionMode = value;
    }

    @Autowired
    public void configureEnvironment(MobileEnvironment environment) {
        this.environment = environment;
    }

    public MobileConnectionService(PilotDataDir data, SecurityProperties security, ObjectMapper mapper,
        @Value("${pilot.mobile.libraries:}") String libraries, @Value("${pilot.mobile.relay:}") String relay) {
        this.data = data.file("mobile");
        this.maintenance = data.maintenance();
        this.security = security;
        this.mapper = mapper;
        this.configuredLibraries = libraries;
        this.relay = relay;
    }

    @EventListener
    public synchronized void serverReady(WebServerInitializedEvent event) {
        port = event.getWebServer().getPort();
        if (Files.exists(data.resolve("enabled"))) {
            watch();
        }
    }

    private void watch() {
        if (watching || closed) {
            return;
        }
        watching = true;
        supervisor.scheduleWithFixedDelay(() -> maintenance.whenAvailable(this::recover), 0, 5, TimeUnit.SECONDS);
    }

    /** 手机在外时也能恢复连接组件；失败后退避，关闭应用后不再启动子进程。 */
    private synchronized void recover() {
        if (closed || (process != null && process.isAlive())
            || (retryAfter != 0 && System.nanoTime() - retryAfter < 0)) {
            return;
        }
        try {
            request("status", null);
            failures = 0;
            retryAfter = 0;
        } catch (RuntimeException e) {
            int seconds = Math.min(60, 5 << Math.min(failures++, 4));
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            // 异常原因可能含子进程地址或管理凭证，日志只记录固定文案。
            log.warn("手机连接组件恢复失败，将在 {} 秒后重试；可在连接手机窗口查看状态", seconds);
        }
    }

    public synchronized JsonNode request(String operation, JsonNode body) {
        if ("repair".equals(operation)) {
            if (environment == null) {
                throw new IllegalStateException("当前运行方式未启用自动环境补全");
            }
            environment.repair();
            return environmentStatus();
        }
        if (environment != null) {
            environment.ensureChecked();
        }
        if (environment != null && !environment.status().ready()) {
            if ("status".equals(operation)) {
                MobileEnvironment.Status state = environment.status();
                logStatusDiagnostic("environment=" + state.state(), "手机连接诊断：环境状态={}，二维码尚未生成", state.state());
                return environmentStatus();
            }
            throw new IllegalStateException(environment.status().message());
        }
        String path = switch (operation) {
            case "status" -> "/api/status";
            case "pair" -> "/api/pair";
            case "revoke" -> "/api/revoke";
            default -> throw new IllegalArgumentException("不支持的设备操作");
        };
        try {
            start();
            HttpRequest.Builder request = HttpRequest.newBuilder(admin.resolve(path)).timeout(ADMIN_REQUEST_TIMEOUT)
                .header("X-Pilot-Pairing", token);
            if ("status".equals(operation)) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode result = mapper.readTree(response.body());
            if (response.statusCode() != 200) {
                if ("pair".equals(operation)) {
                    log.warn("手机连接诊断：配对码申请失败，桥接服务 HTTP 状态码={}", response.statusCode());
                }
                throw new IllegalStateException(result.path("error").asText("手机连接暂不可用，请重试"));
            }
            if ("status".equals(operation) && result.path("version").asInt() != 2) {
                throw new IllegalStateException("手机连接组件版本不兼容，请更新完整安装包");
            }
            if ("status".equals(operation)) {
                boolean invite = !result.path("invite").isMissingNode() && !result.path("invite").isNull();
                int devices = result.path("devices").isArray() ? result.path("devices").size() : 0;
                String pairing = result.path("pairingState").asText("unknown");
                String mode = result.path("connectionMode").asText("unknown");
                String key = "pairing=" + pairing + ",invite=" + invite + ",devices=" + devices + ",mode=" + mode;
                logStatusDiagnostic(key, "手机连接诊断：配对状态={}，有效配对码={}，已配对设备数={}，连接模式={}{}", pairing, invite, devices, mode,
                    !invite && devices > 0 ? "；前端将显示设备列表，选择‘添加设备’生成新码" : "");
            } else if ("pair".equals(operation)) {
                log.info("手机连接诊断：配对码申请成功，有效配对码={}，二维码图像={}", !result.path("code").asText("").isBlank(),
                    !result.path("image").asText("").isBlank());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连接请求已中断", e);
        } catch (IllegalStateException e) {
            if ("pair".equals(operation)) {
                log.warn("手机连接诊断：配对码申请失败：{}", e.getMessage());
            }
            throw e;
        } catch (RuntimeException e) {
            // 路径非法这类非受检异常也转成可读的 503，界面不该只看到 Internal Server Error。
            throw new IllegalStateException("手机连接服务暂不可用，请重试", e);
        } catch (Exception e) {
            throw new IllegalStateException("手机连接服务暂不可用，请重试", e);
        }
    }

    private void logStatusDiagnostic(String key, String message, Object... arguments) {
        if (!key.equals(lastStatusDiagnostic)) {
            lastStatusDiagnostic = key;
            log.info(message, arguments);
        }
    }

    private JsonNode environmentStatus() {
        var state = environment.status();
        return JsonNodeFactory.instance.objectNode().put("version", 2).put("running", false).set("environment",
            JsonNodeFactory.instance.objectNode().put("state", state.state()).put("message", state.message()));
    }

    private void start() throws Exception {
        if (closed) {
            throw new IllegalStateException("Pilot 已关闭，请启动后重新连接");
        }
        if (process != null && process.isAlive() && admin != null) {
            return;
        }
        if (port == 0) {
            throw new IllegalStateException("Pilot 正在启动，请稍后重试");
        }
        if (environment == null || environment.prepared() == null) {
            requirePlatform(HostOs.name(), System.getProperty("os.arch", ""));
        }
        Path libraries = libraries();
        Files.createDirectories(data);
        String executable = BootstrapPlatform.current().executable("java");
        Path javaHome =
            environment != null && environment.prepared() != null && environment.prepared().javaHome() != null
                ? environment.prepared().javaHome() : Path.of(System.getProperty("java.home"));
        log.info("手机连接诊断：启动连接桥，系统={} {}，Java {}，连接模式={}，已配置自定义中继={}，参数 no-open=true",
            System.getProperty("os.name", "unknown"), System.getProperty("os.arch", "unknown"),
            System.getProperty("java.version", "unknown"), connectionMode, !relay.isBlank());
        List<String> args = new ArrayList<>(List.of(javaHome.resolve("bin").resolve(executable).toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", MobileBridgeLayout.classpath(libraries),
            MobileBridgeLayout.MAIN_CLASS, "--pilot-port", Integer.toString(port), "--data-dir", data.toString(),
            "--no-open", "--parent-pid", Long.toString(ProcessHandle.current().pid()), "--name", computerName()));
        if (!relay.isBlank()) {
            args.addAll(List.of("--relay", relay));
        }
        if ("direct".equals(connectionMode)) {
            if (!relay.isBlank()) {
                throw new IllegalStateException("direct 模式不能同时配置中继，请清空 pilot.mobile.relay 或选择 auto");
            }
            args.add("--no-relay");
        }
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.environment().put("PILOT_SECURITY_TOKEN", security.token());
        Process child;
        try {
            child = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动手机连接的 Java 子进程，请检查 Java 安装、执行权限或安全软件拦截", e);
        }
        process = child;
        log.info("手机连接诊断：连接桥子进程已启动，PID={}", child.pid());
        BridgeStartupDiagnostics diagnostics = new BridgeStartupDiagnostics(HostOs.name());
        CompletableFuture<Void> errorsRead = new CompletableFuture<>();
        AtomicBoolean buildMarkerSeen = new AtomicBoolean();
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    diagnostics.accept(line);
                }
            } catch (IOException e) {
                // 管道在退出时可能被关闭；未知错误保留固定提示，不输出原始异常。
            } finally {
                errorsRead.complete(null);
            }
        });
        CompletableFuture<URI> ready = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("PILOT_MOBILE_BRIDGE_BUILD ")) {
                        buildMarkerSeen.set(true);
                        log.info("手机连接诊断：{}", line);
                    }
                    if (line.startsWith("PILOT_MOBILE_ADMIN ")) {
                        ready.complete(validateAdmin(line.substring("PILOT_MOBILE_ADMIN ".length())));
                    }
                }
                ready.completeExceptionally(new IllegalStateException("连接组件已退出，请检查安装包是否支持当前系统"));
            } catch (Exception e) {
                ready.completeExceptionally(e);
            }
        });
        try {
            URI address = ready.get(30, TimeUnit.SECONDS);
            if (!buildMarkerSeen.get()) {
                log.warn("手机连接诊断：连接桥未报告当前构建标记，可能使用了旧版 bridge.jar；请使用当前完整安装包或重新构建连接桥");
            }
            token = address.getFragment();
            admin = new URI("http", null, "127.0.0.1", address.getPort(), "/", null, null);
            Files.writeString(data.resolve("enabled"), "true");
            watch();
        } catch (InterruptedException e) {
            child.destroyForcibly();
            admin = null;
            throw e;
        } catch (Exception e) {
            child.destroyForcibly();
            admin = null;
            try {
                errorsRead.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // 不能让错误诊断阻止退出，最多等待一秒读取已分类的故障。
            }
            throw new IllegalStateException(diagnostics.message(), e);
        }
    }

    static URI validateAdmin(String value) {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() <= 0
            || uri.getUserInfo() != null || uri.getQuery() != null || !"/".equals(uri.getPath())
            || uri.getFragment() == null || !uri.getFragment().matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("无效的连接组件地址");
        }
        return uri;
    }

    static void requirePlatform(String os, String arch) {
        if (!BootstrapPlatform.detect(os, arch).mobileSupported()) {
            throw new IllegalStateException("当前连接组件支持 Apple Silicon、Windows x64、Linux x64/ARM64；此系统架构暂不支持");
        }
    }

    private Path libraries() {
        if (environment != null && environment.prepared() != null && environment.prepared().libraries() != null) {
            return environment.prepared().libraries();
        }
        if (!configuredLibraries.isBlank()) {
            Path path = Path.of(configuredLibraries).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
            throw new IllegalStateException("配置的手机连接组件目录不存在，请检查 pilot.mobile.libraries");
        }
        Path found = SourceLayout.mobileLibraries(SourceLayout.workingDirectory());
        if (found != null) {
            return found;
        }
        throw new IllegalStateException("未找到手机连接组件，源码运行请先执行 node scripts/mobile.mjs build-bridge；安装版请更新完整安装包");
    }

    private static String computerName() {
        String value = System.getenv("COMPUTERNAME");
        if (value == null || value.isBlank()) {
            value = System.getenv("HOSTNAME");
        }
        return value == null || value.isBlank() ? "我的电脑" : value.substring(0, Math.min(80, value.length()));
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        supervisor.shutdownNow();
        if (process != null) {
            process.destroy();
        }
        admin = null;
    }
}
