package com.pilot.core.browser;

import com.pilot.core.tool.Availability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用 Node 子进程驱动 Playwright Chromium 的 {@link BrowserBackend}。
 *
 * <p>sidecar（{@code browser/browser-sidecar.mjs}）通过 stdin/stdout 的 JSON Lines 通信。
 * 整个进程共享一个 Chromium，每个 Pilot 会话一个独立的浏览器上下文。
 *
 * <p>能力声明遵循「握手之后才算数」：{@link #availability()} 只在 {@link #probe()} 成功
 * （进程起来、能加载 playwright-core、能真的启动一次浏览器）之后才返回可用；
 * 探测失败的原因会原样交给模型，避免它不断重试一个永远不会成功的调用。
 * 探测失败后会按间隔自动重测，用户事后装好 Chrome 不需要重启服务。
 *
 * <p>构造时传入的 {@code environment} 会合并到 sidecar 进程环境（如
 * {@code PLAYWRIGHT_BROWSERS_PATH}、{@code PILOT_BROWSER_HEADLESS}），
 * 让 Java 配置驱动浏览器选型与有头/无头，sidecar 自己不读任何配置文件。
 */
public final class PlaywrightBackend implements BrowserBackend, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PlaywrightBackend.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final long REPROBE_INTERVAL_MS = 60_000L;
    private static final long IDLE_SHUTDOWN_MS = 10 * 60_000L;

    private final Path node;
    private final Path script;
    private final Path home;
    private final ObjectMapper mapper;
    private final long idleShutdownMs;
    /** 合并进 sidecar 进程环境的变量（不可变）；构造器重载里默认为空。 */
    private final Map<String, String> environment;
    /** sidecar 主动事件的分发器：单线程保证到达顺序，listener 内部自行 latest-wins。 */
    private final ExecutorService events = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pilot-browser-events");
        thread.setDaemon(true);
        return thread;
    });

    private volatile BrowserEventListener listener;
    /** sidecar 是否声明了镜像事件能力（握手 ping 的 capabilities 协商结果）。 */
    private volatile boolean mirrorCapable;

    private final Object lifecycle = new Object();
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionGeneration = new ConcurrentHashMap<>();
    /** 正在被右侧面板镜像的会话：有人看就不做空闲回收。 */
    private final java.util.Set<String> mirroredSessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean probing = new AtomicBoolean();

    private Process process;
    private OutputStream stdin;
    private CompletableFuture<JsonNode> ready;
    private long generation;
    private volatile long lastUsed = System.currentTimeMillis();
    private volatile long lastProbeAt;
    private volatile Availability state = Availability.unavailable("浏览器运行时尚未检测");
    private volatile Thread reaper;
    private volatile boolean closed;

    public PlaywrightBackend(Path node, Path script, Path home, ObjectMapper mapper) {
        this(node, script, home, mapper, IDLE_SHUTDOWN_MS);
    }

    PlaywrightBackend(Path node, Path script, Path home, ObjectMapper mapper, long idleShutdownMs) {
        this(node, script, home, mapper, idleShutdownMs, Map.of());
    }

    /** @param environment 合并到 sidecar 进程的额外环境变量；内部会拷贝一份 */
    public PlaywrightBackend(Path node, Path script, Path home, ObjectMapper mapper, long idleShutdownMs,
                            Map<String, String> environment) {
        this.node = node;
        this.script = script;
        this.home = home;
        this.mapper = mapper;
        this.idleShutdownMs = idleShutdownMs;
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Availability availability() {
        Availability current = state;
        if (!current.available() && !closed && System.currentTimeMillis() - lastProbeAt > REPROBE_INTERVAL_MS
                && lastProbeAt != 0 && probing.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> {
                try {
                    probe();
                } finally {
                    probing.set(false);
                }
            }, "pilot-browser-reprobe");
            thread.setDaemon(true);
            thread.start();
        }
        return current;
    }

    /** 同步握手：成功才把能力标记为可用。可重复调用。 */
    public Availability probe() {
        lastProbeAt = System.currentTimeMillis();
        try {
            JsonNode reply = request(null, "probe", Map.of(), PROBE_TIMEOUT);
            state = Availability.ready();
            mirrorCapable = declaresMirror(reply.path("capabilities"));
            log.info("浏览器运行时可用：{}", reply.path("text").asString(""));
        } catch (BrowserException e) {
            state = Availability.unavailable(friendly(e.getMessage()));
            log.warn("浏览器运行时不可用：{}", state.reason());
            stopProcess();
        }
        lastProbeAt = System.currentTimeMillis();
        return state;
    }

    private static boolean declaresMirror(JsonNode capabilities) {
        if (!capabilities.isArray()) return false;
        for (JsonNode item : capabilities) {
            if ("mirror".equals(item.asString())) return true;
        }
        return false;
    }

    @Override
    public Reply execute(String sessionId, Map<String, Object> command, Duration timeout) {
        Availability current = state;
        if (!current.available()) {
            throw new BrowserException("浏览器当前不可用：" + current.reason());
        }
        Map<String, Object> arguments = new LinkedHashMap<>(command);
        String op = String.valueOf(arguments.remove("op"));
        JsonNode reply;
        try {
            reply = request(sessionId, op, arguments, timeout);
        } catch (BrowserException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.startsWith("浏览器启动失败") || message.startsWith("没有找到可用的")) {
                state = Availability.unavailable(message);
            }
            throw new BrowserException(restartNotice(sessionId) + message);
        }
        String prefix = restartNotice(sessionId);
        String screenshot = reply.path("screenshot").asString("");
        return new Reply(prefix + reply.path("text").asString(""), screenshot.isBlank() ? null : screenshot);
    }

    @Override
    public void closeSession(String sessionId) {
        sessionGeneration.remove(sessionId);
        mirroredSessions.remove(sessionId);
        synchronized (lifecycle) {
            if (process == null || !process.isAlive()) return;
        }
        try {
            request(sessionId, "close", Map.of(), Duration.ofSeconds(5));
        } catch (BrowserException ignored) {
            // 进程已退出时，会话本来就不存在了
        }
    }

    @Override
    public void close() {
        closed = true;
        events.shutdownNow();
        stopProcess();
    }

    @Override
    public void setEventListener(BrowserEventListener listener) {
        this.listener = listener;
    }

    /** sidecar 是否声明镜像事件能力（capabilities 握手结果）。 */
    public boolean mirrorCapable() {
        return mirrorCapable;
    }

    @Override
    public void mirrorControl(String sessionId, boolean start, int maxWidth, int quality) {
        // 订阅期间视为「有人在看」，空闲回收跳过（见 startReaper）
        if (sessionId != null) {
            if (start) {
                mirroredSessions.add(sessionId);
            } else {
                mirroredSessions.remove(sessionId);
            }
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("maxWidth", maxWidth);
        arguments.put("quality", quality);
        try {
            request(sessionId, start ? "mirror-start" : "mirror-stop", arguments, Duration.ofSeconds(10));
        } catch (BrowserException ignored) {
            // 运行时未就绪或无活动页面：sidecar 已登记镜像请求，浏览器启动后自然生效。
        }
    }

    @Override
    public void focusPage(String sessionId) {
        try {
            request(sessionId, "focus", Map.of(), Duration.ofSeconds(10));
        } catch (BrowserException ignored) {
            // 没有活动页面，无窗可置
        }
    }

    /** sidecar 的主动事件：从协议读取线程转交到单线程事件流，不阻塞 req/resp。 */
    private void dispatchEvent(JsonNode node) {
        BrowserEventListener current = listener;
        if (current == null) return;
        String sessionId = node.path("session").asString("");
        String kind = node.path("event").asString("");
        try {
            events.execute(() -> {
                try {
                    current.onEvent(sessionId, kind, node);
                } catch (RuntimeException e) {
                    log.debug("浏览器事件处理失败：{}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // close 之后进程退出前的尾随事件：随运行时一起丢弃
        }
    }

    /** 进程在会话使用期间被重启过（空闲回收、超时重置、崩溃）时，提醒模型页面状态已丢失。 */
    private String restartNotice(String sessionId) {
        long current;
        synchronized (lifecycle) {
            current = generation;
        }
        Long previous = sessionGeneration.put(sessionId, current);
        return previous != null && previous != current
                ? "（浏览器进程自上次使用后重启过，此前打开的页面和登录状态已丢失，请重新 navigate。）\n"
                : "";
    }

    private JsonNode request(String sessionId, String op, Map<String, Object> arguments, Duration timeout) {
        long id = ids.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            Map<String, Object> message = new HashMap<>(arguments);
            message.put("id", id);
            message.put("op", op);
            if (sessionId != null) message.put("session", sessionId);
            String line = mapper.writeValueAsString(message);
            synchronized (lifecycle) {
                ensureStarted();
                lastUsed = System.currentTimeMillis();
                stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            JsonNode reply = future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!reply.path("ok").asBoolean(false)) {
                throw new BrowserException(reply.path("error").asString("浏览器操作失败"));
            }
            return reply;
        } catch (TimeoutException e) {
            // 超时后 sidecar 的状态不可信：直接重置，下次调用重新拉起。
            stopProcess();
            throw new BrowserException("浏览器操作超过 " + timeout.toSeconds() + " 秒没有响应，已重置浏览器；页面状态已丢失，请重新 navigate 后再试。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException("浏览器操作被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof BrowserException be ? be : new BrowserException(String.valueOf(cause));
        } catch (IOException e) {
            stopProcess();
            throw new BrowserException("无法与浏览器进程通信：" + e.getMessage());
        } finally {
            pending.remove(id);
        }
    }

    /** 调用方必须持有 {@code lifecycle}。 */
    private void ensureStarted() throws IOException {
        if (closed) throw new BrowserException("浏览器运行时已关闭");
        if (process != null && process.isAlive()) return;
        if (!Files.isRegularFile(node)) {
            throw new BrowserException("找不到 Node.js（" + node + "），无法启动浏览器 sidecar。");
        }
        if (!Files.isRegularFile(script)) {
            throw new BrowserException("浏览器 sidecar 脚本缺失：" + script);
        }
        Files.createDirectories(home);
        ProcessBuilder builder = new ProcessBuilder(node.toString(), script.toString());
        builder.directory(home.toFile());
        if (!environment.isEmpty()) {
            builder.environment().putAll(environment);
        }
        builder.redirectError(ProcessBuilder.Redirect.appendTo(home.resolve("sidecar.log").toFile()));
        Process started = builder.start();
        process = started;
        stdin = started.getOutputStream();
        generation++;
        CompletableFuture<JsonNode> handshake = new CompletableFuture<>();
        ready = handshake;
        Thread reader = new Thread(() -> read(started, handshake), "pilot-browser-reader");
        reader.setDaemon(true);
        reader.start();
        startReaper();
        try {
            handshake.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            started.destroyForcibly();
            process = null;
            throw new BrowserException("浏览器 sidecar 启动失败：" + (e instanceof TimeoutException ? "握手超时" : e.getMessage()));
        }
    }

    private void read(Process owner, CompletableFuture<JsonNode> handshake) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (RuntimeException e) {
                    continue;
                }
                // 主动事件（无 id）走事件通道，其余仍是 req/resp 配对。
                if (node.has("event")) {
                    dispatchEvent(node);
                    continue;
                }
                long id = node.path("id").asLong(-1);
                if (id == 0) {
                    handshake.complete(node);
                    continue;
                }
                CompletableFuture<JsonNode> future = pending.get(id);
                if (future != null) future.complete(node);
            }
        } catch (IOException ignored) {
            // 进程退出会关闭管道
        } finally {
            BrowserException gone = new BrowserException("浏览器进程已退出");
            handshake.completeExceptionally(gone);
            synchronized (lifecycle) {
                if (process == owner) {
                    process = null;
                    pending.values().forEach(future -> future.completeExceptionally(gone));
                }
            }
        }
    }

    private void startReaper() {
        if (reaper != null || idleShutdownMs <= 0) return;
        Thread thread = new Thread(() -> {
            long interval = Math.max(50, Math.min(30_000, idleShutdownMs / 2));
            while (!closed) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    return;
                }
                // 面板正在显示某一页（镜像订阅中）就不算空闲：用户盯着页面看，不能把浏览器回收掉
                if (!mirroredSessions.isEmpty()) {
                    lastUsed = System.currentTimeMillis();
                    continue;
                }
                boolean idle = System.currentTimeMillis() - lastUsed > idleShutdownMs;
                if (idle && pending.isEmpty()) {
                    synchronized (lifecycle) {
                        if (process != null && System.currentTimeMillis() - lastUsed > idleShutdownMs) {
                            log.info("浏览器空闲超过 {} 秒，关闭 sidecar 释放内存", idleShutdownMs / 1000);
                            destroyLocked();
                        }
                    }
                }
            }
        }, "pilot-browser-reaper");
        thread.setDaemon(true);
        reaper = thread;
        thread.start();
    }

    private void stopProcess() {
        synchronized (lifecycle) {
            destroyLocked();
        }
    }

    private void destroyLocked() {
        Process current = process;
        process = null;
        if (current == null) return;
        BrowserException gone = new BrowserException("浏览器已关闭");
        pending.values().forEach(future -> future.completeExceptionally(gone));
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 已经关了
        }
        // 先让 sidecar 自己收尾关掉 Chromium（stdin 关闭即退出），超时再强杀。
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.descendants().forEach(ProcessHandle::destroyForcibly);
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private static String friendly(String message) {
        String text = message == null ? "" : message;
        if (text.contains("Cannot find module") || text.contains("playwright-core")) {
            return "浏览器运行时依赖尚未安装（playwright-core 未就绪）。服务启动时会自动安装，请稍后再试。";
        }
        return text;
    }
}
