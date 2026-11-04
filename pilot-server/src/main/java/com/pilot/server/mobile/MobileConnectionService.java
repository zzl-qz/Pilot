package com.pilot.server.mobile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.security.SecurityProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 连接桥随后台服务存活，Web 与桌面共用设备身份和授权。管理令牌不传给前端。 */
@Service
public class MobileConnectionService {
    private static final Logger log = LoggerFactory.getLogger(MobileConnectionService.class);
    private final Path data;
    private final String configuredLibraries;
    private final String relay;
    private final SecurityProperties security;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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

    public MobileConnectionService(PilotDataDir data, SecurityProperties security, ObjectMapper mapper,
        @Value("${pilot.mobile.libraries:}") String libraries, @Value("${pilot.mobile.relay:}") String relay) {
        this.data = data.file("mobile");
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
        supervisor.scheduleWithFixedDelay(this::recover, 0, 5, TimeUnit.SECONDS);
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
        String path = switch (operation) {
            case "status" -> "/api/status";
            case "pair" -> "/api/pair";
            case "revoke" -> "/api/revoke";
            default -> throw new IllegalArgumentException("不支持的设备操作");
        };
        try {
            start();
            HttpRequest.Builder request = HttpRequest.newBuilder(admin.resolve(path)).timeout(Duration.ofSeconds(30))
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
                throw new IllegalStateException(result.path("error").asText("手机连接暂不可用，请重试"));
            }
            if ("status".equals(operation) && result.path("version").asInt() != 2) {
                throw new IllegalStateException("手机连接组件版本不兼容，请更新完整安装包");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连接请求已中断", e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("手机连接服务暂不可用，请重试", e);
        }
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
        requirePlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        Path libraries = libraries();
        Files.createDirectories(data);
        String executable =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        List<String> args =
            new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", libraries.resolve("*").toString(),
                "com.pilot.mobile.bridge.MainKt", "--pilot-port", Integer.toString(port), "--data-dir", data.toString(),
                "--no-open", "--parent-pid", Long.toString(ProcessHandle.current().pid()), "--name", computerName()));
        if (!relay.isBlank()) {
            args.addAll(List.of("--relay", relay));
        }
        ProcessBuilder builder = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PILOT_SECURITY_TOKEN", security.token());
        Process child = builder.start();
        process = child;
        CompletableFuture<URI> ready = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
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
            token = address.getFragment();
            admin = new URI("http", null, "127.0.0.1", address.getPort(), "/", null, null);
            Files.writeString(data.resolve("enabled"), "true");
            watch();
        } catch (Exception e) {
            child.destroyForcibly();
            admin = null;
            throw new IllegalStateException("手机连接组件未能启动，请检查安装包或稍后重试", e);
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
        String platform = os.toLowerCase(Locale.ROOT);
        boolean arm = "aarch64".equals(arch) || "arm64".equals(arch);
        boolean x64 = "amd64".equals(arch) || "x86_64".equals(arch);
        boolean supported = platform.contains("mac") ? arm
            : platform.contains("win") ? x64 : platform.contains("linux") && (arm || x64);
        if (!supported) {
            throw new IllegalStateException("当前连接组件支持 Apple Silicon、Windows x64、Linux x64/ARM64；此系统架构暂不支持");
        }
    }

    private Path libraries() {
        if (!configuredLibraries.isBlank()) {
            Path path = Path.of(configuredLibraries).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 4 && current != null; depth++, current = current.getParent()) {
            for (String candidate : List.of("mobile-bridge/lib", "pilot-mobile/bridge/build/install/bridge/lib")) {
                Path path = current.resolve(candidate);
                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        }
        throw new IllegalStateException("当前安装缺少手机连接组件，请安装包含移动连接的完整 Pilot 版本");
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
