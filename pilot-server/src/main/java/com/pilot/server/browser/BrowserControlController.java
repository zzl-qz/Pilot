package com.pilot.server.browser;

import com.pilot.core.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        requireSession(id);
        if (!browser.mirrorEnabled()) {
            return ResponseEntity.noContent().build();
        }
        SseEmitter emitter = browser.subscribe(id);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        // 页面刷新、折叠侧栏或关页都会让连接失效；属于正常生命周期，静默收尾。
        Runnable close = () -> {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> beat = heartbeat.get();
            if (beat != null) beat.cancel(false);
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
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception error) {
                close.run();
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
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

    private void requireSession(String id) {
        if (sessions.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + id);
        }
    }
}
