package com.pilot.server.browser;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserEventListener;
import com.pilot.core.tool.Availability;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 浏览器镜像的订阅分发与上行动作入口（瞬态通道，与持久化事件流完全分开）。
 *
 * <p>sidecar 的镜像帧是高频瞬态数据：每个会话只保留最新一帧（latest-wins），
 * 慢消费者自动丢帧、不排队；帧不落库、不落盘——断线重连靠补发缓存帧即可，
 * 无需游标。若把它塞进 {@code /sessions/{id}/events}（带 sequence、落 pilot.db），
 * 会把库撑爆并污染历史回放，因此这里是独立的 SSE 端点。
 *
 * <p>状态订阅只收导航与生命周期；存在画面订阅者时才开启 screencast，最后一个画面
 * 订阅者离开即停帧。全部订阅断开后释放会话的镜像状态。
 *
 * <p>线程模型：sidecar 事件在 PlaywrightBackend 的单线程事件流里进入 {@link #onEvent}；
 * SSE 写入以 per-state 锁串行化（HTTP 线程的补发与事件线程的广播互斥），
 * mirrorControl / focus 等控制动作统一交给单线程 executor，不阻塞请求与事件线程。
 */
@Service
public class BrowserControlService implements BrowserEventListener {
    private static final Logger log = LoggerFactory.getLogger(BrowserControlService.class);
    /** 用户在界面上手动操作浏览器时，单条指令的等待上限。 */
    private static final Duration USER_COMMAND_TIMEOUT = Duration.ofSeconds(45);

    private final BrowserRuntime runtime;
    private final ObjectMapper mapper;
    private final int maxWidth;
    private final int quality;
    private final boolean enabled;
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();
    /** 控制动作（开/关镜像、置前）串行执行，避免并发拉起重复请求。 */
    private final ExecutorService controls = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pilot-browser-control");
        thread.setDaemon(true);
        return thread;
    });

    public BrowserControlService(BrowserRuntime runtime, ObjectMapper mapper,
        @Value("${pilot.browser.mirror.enabled:true}") boolean enabled,
        @Value("${pilot.browser.mirror.max-width:1280}") int maxWidth,
        @Value("${pilot.browser.mirror.quality:55}") int quality) {
        this.runtime = runtime;
        this.mapper = mapper;
        this.enabled = enabled;
        this.maxWidth = clamp(maxWidth, 320, 1920);
        this.quality = clamp(quality, 20, 90);
        // 事件监听注册在这里而不是 BrowserRuntime 构造时：避免两个 bean 的构造依赖成环。
        runtime.setEventListener(this);
    }

    /** 镜像链路是否开启（pilot.browser.mirror.enabled=false 时端点直接 204）。 */
    public boolean mirrorEnabled() {
        return enabled;
    }

    /** sidecar 是否已声明镜像事件能力（旧 sidecar 无事件通道时不推帧）。 */
    public boolean mirrorCapable() {
        return runtime.mirrorCapable();
    }

    /** 镜像面板的轻量状态：前端据此决定是否显示浏览器 tab、能否连 SSE。 */
    public Map<String, Object> status(String sessionId) {
        Availability availability = runtime.availability();
        return Map.of("enabled", enabled, "capable", runtime.mirrorCapable(), "available", availability.available(),
            "reason", availability.reason(), "paused", runtime.interactionsPaused(sessionId), "headless",
            runtime.headless());
    }

    /** 订阅某个会话的镜像帧；连接建立即补发当前状态与最新一帧。 */
    public SseEmitter subscribe(String sessionId) {
        return subscribe(sessionId, new SseEmitter(0L));
    }

    /** 同上，但允许调用方（控制器/测试）自带 emitter。 */
    public SseEmitter subscribe(String sessionId, SseEmitter emitter) {
        return subscribe(sessionId, emitter, true);
    }

    public SseEmitter subscribeState(String sessionId) {
        return subscribeState(sessionId, new SseEmitter(0L));
    }

    public SseEmitter subscribeState(String sessionId, SseEmitter emitter) {
        return subscribe(sessionId, emitter, false);
    }

    private SseEmitter subscribe(String sessionId, SseEmitter emitter, boolean capture) {
        while (true) {
            State state = states.computeIfAbsent(sessionId, State::new);
            synchronized (state) {
                // 最后一个订阅者可能刚移除了旧 state，不能把新连接挂在失效对象上。
                if (states.get(sessionId) != state) {
                    continue;
                }
                boolean first = state.subscribers.isEmpty();
                boolean wasCapturing = state.capturing();
                state.subscribers.put(emitter, capture);
                if (first || wasCapturing != state.capturing()) {
                    controlCapture(state);
                }
                push(state, emitter, "state", statePayload(sessionId));
                if (state.subscribers.containsKey(emitter) && state.navigation != null) {
                    push(state, emitter, "nav", state.navigation.toString());
                }
                if (state.subscribers.containsKey(emitter) && capture && state.frame != null) {
                    push(state, emitter, "frame", state.frame.toString());
                }
                return emitter;
            }
        }
    }

    /** 订阅者断开（页面关闭/折叠/出错）；最后一个断开时停止该会话的 screencast。幂等。 */
    public void unsubscribe(String sessionId, SseEmitter emitter) {
        State state = states.get(sessionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            drop(state, emitter);
        }
    }

    /**
     * 面板正在直接嵌页面时关掉截图；嵌不进去再打开。
     * 仍然保持订阅，浏览器不会因为没人看截图就被空闲回收。
     */
    public void setCapture(String sessionId, boolean capture) {
        State state = states.get(sessionId);
        if (state != null) {
            synchronized (state) {
                boolean before = state.capturing();
                state.captureEnabled = capture;
                if (before != state.capturing()) {
                    controlCapture(state);
                }
            }
        }
    }

    /** 把某个会话的浏览器窗口置前（不置顶循环）。 */
    public void focus(String sessionId) {
        controls.execute(() -> runtime.focusPage(sessionId));
    }

    /**
     * 用户在内置浏览器面板里发起的动作：导航（navigate/back/forward/reload）与页面交互
     * （mouse/keys）。这些是本机用户自己的操作，不受「接管」开关与工具审批约束——
     * 接管正是为了让用户自己动手；面板与智能体操作的是同一个页面。
     *
     * @param op   sidecar 协议里的操作名
     * @param args 该操作的参数（坐标、文本、URL…）
     * @return 给前端的结果（成功与否、文本提示）
     */
    public Map<String, Object> userAction(String sessionId, String op, Map<String, Object> args) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("op", op);
        if (args != null) {
            command.putAll(args);
        }
        // 只由用户面板入口设置；模型 BrowserTool 不转发此字段。
        command.put("op", op);
        command.put("userInitiated", true);
        try {
            BrowserBackend.Reply reply = runtime.execute(sessionId, command, USER_COMMAND_TIMEOUT);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("text", reply.text() == null ? "" : reply.text());
            return result;
        } catch (RuntimeException e) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("ok", false);
            failure.put("error", e.getMessage() == null ? "浏览器操作失败" : e.getMessage());
            return failure;
        }
    }

    /**
     * 用户接管：暂停/恢复智能体的页面交互动作（观察类不受影响）。
     * 状态存在 BrowserRuntime（唯一事实源），变化经 state 事件回推全部订阅者，
     * 前端按钮只是它的投影——双击/多端不会产生不一致。
     */
    public void setPaused(String sessionId, boolean paused) {
        runtime.setInteractionsPaused(sessionId, paused);
        State state = states.get(sessionId);
        if (state != null) {
            broadcast(state, "state", statePayload(sessionId));
        }
    }

    @Override
    public void onEvent(String sessionId, String kind, JsonNode payload) {
        switch (kind) {
            case "mirror-frame" -> {
                State state = states.get(sessionId);
                if (state == null) {
                    return;
                }
                synchronized (state) {
                    if (state.capturing()) {
                        state.frame = payload; // latest-wins；无画面消费者时不保留帧。
                        broadcast(state, "frame", payload.toString());
                    }
                }
            }
            case "agent-action" -> broadcastTo(sessionId, "action", payload);
            case "nav" -> {
                State state = states.get(sessionId);
                if (state != null) {
                    synchronized (state) {
                        state.navigation = payload;
                        broadcast(state, "nav", payload.toString());
                    }
                }
            }
            case "browser-closed" -> {
                // 进程级事件：浏览器断了（空闲回收/崩溃/用户手关），提示所有会话并清掉缓存帧。
                for (State state : states.values()) {
                    synchronized (state) {
                        state.frame = null;
                        state.navigation = null;
                        broadcast(state, "closed", payload.toString());
                    }
                }
            }
            default -> {
                // 未知事件忽略，向前兼容新 sidecar
            }
        }
    }

    /** 清理控制线程；Spring 关闭时由容器回调。 */
    @PreDestroy
    public void shutdown() {
        controls.shutdownNow();
    }

    // ---- 内部 ---------------------------------------------------------------------------

    private void broadcastTo(String sessionId, String name, JsonNode payload) {
        State state = states.get(sessionId);
        if (state == null) {
            return;
        }
        broadcast(state, name, payload.toString());
    }

    private void broadcast(State state, String name, String data) {
        synchronized (state) {
            for (SseEmitter emitter : List.copyOf(state.subscribers.keySet())) {
                if (!name.equals("frame") || Boolean.TRUE.equals(state.subscribers.get(emitter))) {
                    push(state, emitter, name, data);
                }
            }
        }
    }

    /** 必须持有 state 锁；写失败（对端断开）时移除订阅，最后一个断开时联动停帧。 */
    private void push(State state, SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException error) {
            // ClientAbortException 等：对端已断，静默收尾并摘掉订阅
            drop(state, emitter);
        }
    }

    /** 必须持有 state 锁。 */
    private void drop(State state, SseEmitter emitter) {
        boolean wasCapturing = state.capturing();
        if (state.subscribers.remove(emitter) == null) {
            return;
        }
        if (!state.subscribers.isEmpty()) {
            if (wasCapturing != state.capturing()) {
                controlCapture(state);
            }
            return;
        }
        // 订阅清空：停帧、清缓存帧，接管开关一并复位（没人看着页面就不算接管中）
        runtime.setInteractionsPaused(state.id, false);
        state.frame = null;
        controls.execute(() -> runtime.mirrorControl(state.id, false, maxWidth, quality));
        // 先排入旧连接的停止命令，再允许新 state 被创建，避免旧停止命令覆盖新订阅。
        states.remove(state.id, state);
    }

    /** 必须持有 state 锁，保证订阅切换和控制命令的顺序一致。 */
    private void controlCapture(State state) {
        boolean capture = state.capturing();
        if (!capture) {
            state.frame = null;
        }
        controls.execute(() -> {
            if (capture) {
                runtime.mirrorControl(state.id, true, maxWidth, quality);
            } else {
                runtime.mirrorCapture(state.id, false, maxWidth, quality);
            }
        });
    }

    private String statePayload(String sessionId) {
        return mapper.writeValueAsString(Map.of("sessionId", sessionId, "paused", runtime.interactionsPaused(sessionId),
            "available", runtime.availability().available(), "headless", runtime.headless()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 每个会话的镜像状态：最新一帧 + 订阅者表。 */
    private static final class State {
        final String id;
        final Map<SseEmitter, Boolean> subscribers = new LinkedHashMap<>();
        JsonNode frame;
        JsonNode navigation;
        boolean captureEnabled = true;

        boolean capturing() {
            return captureEnabled && subscribers.containsValue(true);
        }

        State(String id) {
            this.id = id;
        }
    }
}
