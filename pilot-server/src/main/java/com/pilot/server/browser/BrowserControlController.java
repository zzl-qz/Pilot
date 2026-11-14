package com.pilot.server.browser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.SessionLookup;

/**
 * 浏览器镜像面板的 HTTP 入口：瞬态 SSE 帧流 + 置前等上行动作。
 *
 * <p>与 {@code SessionController#events} 的持久化事件流刻意分开——镜像帧不落库、无游标，
 * 断线重连由 {@link BrowserControlService} 补发缓存帧。EventSource 不发 onerror 之外的信息，
 * 因此前端先查 {@code /browser/status} 再决定是否连流，避免对着 204 无限重连。
 */
@RestController
@RequestMapping("/api/sessions")
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

    public BrowserControlController(SessionService sessions, BrowserControlService browser) {
        this.sessions = sessions;
        this.browser = browser;
    }

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
        heartbeat.set(HEARTBEAT.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception error) {
                close.run();
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS));
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
    public ResponseEntity<Void> pause(@PathVariable String id) {
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
    public ResponseEntity<Void> resume(@PathVariable String id) {
        requireSession(id);
        browser.setPaused(id, false);
        return ResponseEntity.noContent().build();
    }

    /** 地址栏：用户直接输入地址打开页面（简写 localhost:5173 会自动补 http://）。 */
    @PostMapping("/{id}/browser/navigate")
    public Map<String, Object> navigate(@PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body) {
        requireSession(id);
        String raw = body == null ? null : String.valueOf(body.getOrDefault("url", "")).strip();
        if (raw.isEmpty()) {
            return Map.of("ok", false, "error", "请先输入要打开的地址");
        }
        return browser.userAction(id, "navigate", Map.of("url", withScheme(raw)));
    }

    @PostMapping("/{id}/browser/back")
    public Map<String, Object> back(@PathVariable String id) {
        requireSession(id);
        return browser.userAction(id, "back", Map.of());
    }

    @PostMapping("/{id}/browser/forward")
    public Map<String, Object> forward(@PathVariable String id) {
        requireSession(id);
        return browser.userAction(id, "forward", Map.of());
    }

    @PostMapping("/{id}/browser/reload")
    public Map<String, Object> reload(@PathVariable String id) {
        requireSession(id);
        return browser.userAction(id, "reload", Map.of());
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
        return browser.userAction(id, "mouse", args);
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
        return browser.userAction(id, "pick", args);
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
        return browser.userAction(id, "resize", args);
    }

    /** 面板里的「停止加载」：中断当前导航，页面会停在当前状态。 */
    @PostMapping("/{id}/browser/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        requireSession(id);
        return browser.userAction(id, "stop", Map.of());
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
        return browser.userAction(id, op, args);
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
        return browser.userAction(id, "keys", args);
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
