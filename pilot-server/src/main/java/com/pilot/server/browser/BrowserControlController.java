package com.pilot.server.browser;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pilot.core.session.SessionService;
import com.pilot.server.session.lookup.SessionLookup;

import lombok.RequiredArgsConstructor;

/**
 * 浏览器镜像面板的 HTTP 入口：瞬态 SSE 帧流 + 置前等上行动作。
 *
 * <p>与 {@code SessionController#events} 的持久化事件流刻意分开——镜像帧不落库、无游标，
 * 断线重连由 {@link BrowserControlService} 补发缓存帧。EventSource 不发 onerror 之外的信息，
 * 因此前端先查 {@code /browser/status} 再决定是否连流，避免对着 204 无限重连。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class BrowserControlController {
    /** SSE 心跳：空闲也要定期写，对端早已断开时下一次写失败才能把订阅清掉。 */
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pilot-browser-mirror-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private static final long HEARTBEAT_SECONDS = 15L;

    private final SessionService sessions;
    private final BrowserControlService browser;

    /** 镜像面板状态；前端据此决定 tab 显隐与是否连流。 */
    @GetMapping("/{id}/browser/status")
    public Map<String, Object> status(@PathVariable String id) {
        requireSession(id);
        return browser.status(id);
    }

    /** 实时镜像帧流（只下行）；连接建立即补发最新一帧。 */
    @GetMapping(value = "/{id}/browser/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String id) {
        return stream(id, true);
    }

    /** 仅订阅导航、运行状态与关闭事件，不生产或传输截图。 */
    @GetMapping(value = "/{id}/browser/state-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stateStream(@PathVariable String id) {
        return stream(id, false);
    }

    private ResponseEntity<SseEmitter> stream(String id, boolean capture) {
        requireSession(id);
        if (!browser.mirrorEnabled()) {
            return ResponseEntity.noContent().build();
        }
        SseEmitter emitter = capture ? browser.subscribe(id) : browser.subscribeState(id);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Object sendLock = browser.sendLock(emitter);

        // 页面刷新、折叠侧栏或关页都会让连接失效；属于正常生命周期，静默收尾。
        Runnable close = () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> beat = heartbeat.get();
            if (beat != null) {
                beat.cancel(false);
            }
            browser.unsubscribe(id, emitter);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 已经完成
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        ScheduledFuture<?> scheduled = HEARTBEAT.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                ScheduledFuture<?> current = heartbeat.get();
                if (current != null) {
                    current.cancel(false);
                }
                return;
            }
            try {
                synchronized (sendLock) {
                    if (!closed.get()) {
                        emitter.send(SseEmitter.event().comment("ping"));
                    }
                }
            } catch (Exception error) {
                close.run();
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        heartbeat.set(scheduled);
        if (closed.get()) {
            scheduled.cancel(false);
        }
        return ResponseEntity.ok().header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no").body(emitter);
    }

    /** 把该会话的浏览器窗口置前；用户不点不抢焦点。 */
    @PostMapping("/{id}/browser/focus")
    public ResponseEntity<Void> focus(@PathVariable String id) {
        requireSession(id);
        if (browser.mirrorEnabled()) {
            browser.focus(id);
        }
        return ResponseEntity.noContent().build();
    }

    /** 用户接管：把页面留给用户，智能体的交互/导航动作被温和拦截（观察类放行）。 */
    @PostMapping("/{id}/browser/pause")
    public ResponseEntity<Void> pause(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        browser.setPaused(id, true);
        return ResponseEntity.noContent().build();
    }

    /** 直接嵌页面时关掉截图流；页面禁止被嵌入时再打开。 */
    @PostMapping("/{id}/browser/capture")
    public ResponseEntity<Void> capture(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        boolean on = body != null && Boolean.TRUE.equals(body.get("capture"));
        browser.setCapture(id, on);
        return ResponseEntity.noContent().build();
    }

    /** 恢复智能体的页面操作。 */
    @PostMapping("/{id}/browser/resume")
    public ResponseEntity<Void> resume(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        browser.setPaused(id, false);
        return ResponseEntity.noContent().build();
    }

    /** 立即终止智能体对页面的自动控制，清除蓝色控制框并暂停后续自动动作。 */
    @PostMapping("/{id}/browser/stop-control")
    public Map<String, Object> stopControl(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        return browser.stopControl(id, browserSessionId(body));
    }

    /** 完成接管并唤醒等待中的浏览器或子代理操作。 */
    @PostMapping("/{id}/browser/handoff/resume")
    public Map<String, Object> resumeHandoff(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        String target = browserSessionId(body);
        String note = body == null ? "" : text(body.get("note"), 500);
        return browser.resumeHandoff(id, target, note);
    }

    /** 取消正在等待的接管请求。 */
    @PostMapping("/{id}/browser/handoff/cancel")
    public Map<String, Object> cancelHandoff(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        String target = browserSessionId(body);
        String reason = body == null ? "" : text(body.get("reason"), 500);
        return browser.cancelHandoff(id, target, reason);
    }

    /** 切换镜像控制面板聚焦的浏览器泳道（主会话或某个子代理）。 */
    @PostMapping("/{id}/browser/lane")
    public Map<String, Object> selectLane(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        String target = browserSessionId(body);
        return browser.selectLane(id, target);
    }

    /** 查询本会话的浏览器宿主机证据清单。 */
    @GetMapping("/{id}/browser/evidence")
    public Map<String, Object> evidence(@PathVariable String id, @RequestParam(required = false) String runId,
        @RequestParam(defaultValue = "80") int limit) {
        requireSession(id);
        return browser.evidence(id, runId, Math.max(1, Math.min(limit, 200)));
    }

    /** 按证据编号读取受控目录下的真实截图文件。 */
    @GetMapping("/{id}/browser/evidence/{evidenceId}/image")
    public ResponseEntity<FileSystemResource> evidenceImage(@PathVariable String id, @PathVariable String evidenceId) {
        requireSession(id);
        Optional<Path> resolved = browser.resolveEvidenceImage(id, evidenceId);
        if (resolved.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path file = resolved.get();
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("Cache-Control", "private, max-age=60")
            .body(new FileSystemResource(file));
    }

    /** 按证据编号读取受控目录下的可回放过程录像 JSON。 */
    @GetMapping("/{id}/browser/evidence/{evidenceId}/recording")
    public ResponseEntity<FileSystemResource> evidenceRecording(@PathVariable String id,
        @PathVariable String evidenceId) {
        requireSession(id);
        Optional<Path> resolved = browser.resolveEvidenceRecording(id, evidenceId);
        if (resolved.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "private, max-age=60").body(new FileSystemResource(resolved.get()));
    }

    /** 地址栏：用户直接输入地址打开页面（简写 localhost:5173 会自动补 http://）。 */
    @PostMapping("/{id}/browser/navigate")
    public Map<String, Object> navigate(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        String raw = body == null ? null : String.valueOf(body.getOrDefault("url", "")).strip();
        if (raw == null || raw.isEmpty()) {
            return Map.of("ok", false, "error", "请先输入要打开的地址");
        }
        return browser.userAction(id, browserSessionId(body), "navigate", Map.of("url", withScheme(raw)));
    }

    @PostMapping("/{id}/browser/back")
    public Map<String, Object> back(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        return browser.userAction(id, browserSessionId(body), "back", Map.of());
    }

    @PostMapping("/{id}/browser/forward")
    public Map<String, Object> forward(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        return browser.userAction(id, browserSessionId(body), "forward", Map.of());
    }

    @PostMapping("/{id}/browser/reload")
    public Map<String, Object> reload(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        return browser.userAction(id, browserSessionId(body), "reload", Map.of());
    }

    /**
     * 面板里的鼠标动作：click（页面坐标）/ wheel（滚动量）。坐标是 1280×800 视口内的设备像素，
     * 由前端按镜像帧尺寸换算，服务端只做范围裁剪。
     */
    @PostMapping("/{id}/browser/pointer")
    public Map<String, Object> pointer(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        if (body == null) {
            return Map.of("ok", false, "error", "缺少指针参数");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", normalizePointerAction(body.get("action")));
        args.put("x", clamp(coord(body.get("x")), 0, 4096));
        args.put("y", clamp(coord(body.get("y")), 0, 4096));
        args.put("deltaX", clamp(coord(body.get("deltaX")), -4000, 4000));
        args.put("deltaY", clamp(coord(body.get("deltaY")), -4000, 4000));
        return browser.userAction(id, browserSessionId(body), "mouse", args);
    }

    /** 面板里的「选择元素」：把点到的东西描述成一段可粘贴的引用。 */
    @PostMapping("/{id}/browser/pick")
    public Map<String, Object> pick(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        if (body == null) {
            return Map.of("ok", false, "error", "缺少点击坐标");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("x", clamp(coord(body.get("x")), 0, 4096));
        args.put("y", clamp(coord(body.get("y")), 0, 4096));
        return browser.userAction(id, browserSessionId(body), "pick", args);
    }

    /** 面板尺寸变化：把页面视口设成面板大小，画面才能铺满且点击坐标 1:1。 */
    @PostMapping("/{id}/browser/resize")
    public Map<String, Object> resize(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        if (body == null) {
            return Map.of("ok", false, "error", "缺少尺寸");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("width", clamp(coord(body.get("width")), 280, 1920));
        args.put("height", clamp(coord(body.get("height")), 220, 1600));
        return browser.userAction(id, browserSessionId(body), "resize", args);
    }

    /** 面板里的「停止加载」：中断当前导航，页面会停在当前状态。 */
    @PostMapping("/{id}/browser/stop")
    public Map<String, Object> stop(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        return browser.userAction(id, browserSessionId(body), "stop", Map.of());
    }

    /** 面板里的「控制台」：读页面 console 记录（可选 level 过滤 / clear 清空）。 */
    @PostMapping("/{id}/browser/logs")
    public Map<String, Object> logs(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        Map<String, Object> args = new LinkedHashMap<>();
        if (body != null) {
            if (Boolean.TRUE.equals(body.get("clear"))) {
                args.put("clear", true);
            }
            if (body.get("level") != null) {
                args.put("level", String.valueOf(body.get("level")));
            }
        }
        String op = body != null && Boolean.TRUE.equals(body.get("network")) ? "network" : "console";
        return browser.userAction(id, browserSessionId(body), op, args);
    }

    /** 面板里的键盘动作：key 单键（Enter / Backspace / ArrowDown…）或 text 整段文本。 */
    @PostMapping("/{id}/browser/keys")
    public Map<String, Object> keys(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        if (body == null) {
            return Map.of("ok", false, "error", "缺少键盘参数");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        String key = text(body.get("key"), 40);
        if (!key.isEmpty()) {
            args.put("key", key);
        }
        String typed = text(body.get("text"), 2000);
        if (!typed.isEmpty()) {
            args.put("text", typed);
        }
        if (args.isEmpty()) {
            return Map.of("ok", false, "error", "缺少按键内容");
        }
        return browser.userAction(id, browserSessionId(body), "keys", args);
    }

    private static String browserSessionId(Map<String, Object> body) {
        if (body == null || body.get("browserSessionId") == null) {
            return "";
        }
        return String.valueOf(body.get("browserSessionId")).strip();
    }

    private static String normalizePointerAction(Object value) {
        String action = value == null ? "click" : String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "wheel", "move", "click" -> action;
            default -> "click";
        };
    }

    private static String withScheme(String raw) {
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        // 地址栏里写 localhost:5173、127.0.0.1:8080 是最常见的用法，补上协议再交给浏览器
        return "http://" + raw;
    }

    private static double coord(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String text(Object value, int max) {
        String result = value == null ? "" : String.valueOf(value);
        return result.length() > max ? result.substring(0, max) : result;
    }

    private void requireSession(String id) {
        if (sessions.find(id).isEmpty()) {
            throw SessionLookup.missing(id);
        }
    }
}
