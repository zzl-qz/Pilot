package com.pilot.server.browser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserEventListener;
import com.pilot.core.browser.BrowserEvidenceRecord;
import com.pilot.core.browser.BrowserHandoffBroker;
import com.pilot.core.tool.Availability;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class BrowserControlService implements BrowserEventListener {
    /** 用户在界面上手动操作浏览器时，单条指令的等待上限。 */
    private static final Duration USER_COMMAND_TIMEOUT = Duration.ofSeconds(45);

    private final BrowserRuntime runtime;
    private final ObjectMapper mapper;
    private final int maxWidth;
    private final int quality;
    private final boolean enabled;
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<SseEmitter, Object> emitterLocks = new ConcurrentHashMap<>();
    private volatile BrowserHandoffBroker handoffs;
    private volatile BrowserEvidenceService evidence;
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
        this.maxWidth = Math.clamp(maxWidth, 320, 1920);
        this.quality = Math.clamp(quality, 20, 90);
        // 事件监听注册在这里而不是 BrowserRuntime 构造时：避免两个 bean 的构造依赖成环。
        runtime.setEventListener(this);
    }

    @Autowired(required = false)
    public void setHandoffs(BrowserHandoffBroker handoffs) {
        this.handoffs = handoffs;
        if (handoffs != null) {
            handoffs.addListener(this::onHandoffEvent);
        }
    }

    @Autowired(required = false)
    public void setEvidence(BrowserEvidenceService evidence) {
        this.evidence = evidence;
        if (evidence != null) {
            evidence.addListener(this::onEvidenceRecord);
        }
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
        int evidenceCount =
            evidence == null ? 0 : evidence.list(sessionId, "", BrowserEvidenceService.MAX_RECORDS_PER_SESSION).size();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("capable", runtime.mirrorCapable());
        status.put("available", availability.available());
        status.put("reason", availability.reason());
        status.putAll(baseStateMap(sessionId, availability));
        status.put("evidenceCount", evidenceCount);
        return status;
    }

    /** 订阅某个会话的镜像帧；连接建立即补发当前状态与最新一帧。 */
    public Object sendLock(SseEmitter emitter) {
        return emitterLocks.computeIfAbsent(emitter, ignored -> new Object());
    }

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
                JsonNode activeNav = state.laneNavs.getOrDefault(state.activeLane, state.navigation);
                if (state.subscribers.containsKey(emitter) && activeNav != null) {
                    push(state, emitter, "nav", activeNav.toString());
                }
                JsonNode activeAction = state.laneActions.get(state.activeLane);
                if (state.subscribers.containsKey(emitter) && activeAction != null) {
                    push(state, emitter, "action", activeAction.toString());
                }
                if (handoffs != null && state.subscribers.containsKey(emitter)) {
                    List<BrowserHandoffBroker.PendingHandoff> pending = handoffs.pending(sessionId);
                    if (!pending.isEmpty()) {
                        push(state, emitter, "handoff",
                            handoffSnapshotJson(sessionId, "pending", pending.getFirst(), ""));
                    }
                }
                JsonNode activeFrame = state.laneFrames.getOrDefault(state.activeLane, state.frame);
                if (state.subscribers.containsKey(emitter) && capture && activeFrame != null) {
                    push(state, emitter, "frame", activeFrame.toString());
                }
                return emitter;
            }
        }
    }

    /** 订阅者断开（页面关闭/折叠/出错）；最后一个断开时停止该会话的 screencast。幂等。 */
    public void unsubscribe(String sessionId, SseEmitter emitter) {
        emitterLocks.remove(emitter);
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

    /** 把某个会话当前选中的浏览器窗口置前（不置顶循环）。 */
    public void focus(String sessionId) {
        String target = activeLaneOf(sessionId);
        controls.execute(() -> runtime.focusPage(target));
    }

    public Map<String, Object> selectLane(String sessionId, String browserSessionId) {
        String target = browserSessionId == null || browserSessionId.isBlank() ? sessionId : browserSessionId.strip();
        if (!target.equals(sessionId) && !sessionId.equals(runtime.parentSessionOf(target))) {
            return Map.of("ok", false, "error", "目标浏览器通道不属于当前会话");
        }
        State state = states.get(sessionId);
        if (state == null) {
            return Map.of("ok", true, "activeLane", target);
        }
        synchronized (state) {
            String previous = state.activeLane;
            state.activeLane = target;
            state.frame = state.laneFrames.get(target);
            state.navigation = state.laneNavs.get(target);
            switchMirrorLane(state, previous, target);
            broadcast(state, "state", statePayload(sessionId));
            if (state.navigation != null) {
                broadcast(state, "nav", state.navigation.toString());
            }
            JsonNode lastAction = state.laneActions.get(target);
            if (lastAction != null) {
                broadcast(state, "action", lastAction.toString());
            }
            if (state.capturing() && state.frame != null) {
                broadcast(state, "frame", state.frame.toString());
            }
        }
        return Map.of("ok", true, "activeLane", target);
    }

    public Map<String, Object> resumeHandoff(String sessionId, String browserSessionId, String note) {
        return resolveHandoff(sessionId, browserSessionId, target -> handoffs.resume(target, note), true);
    }

    public Map<String, Object> cancelHandoff(String sessionId, String browserSessionId, String reason) {
        return resolveHandoff(sessionId, browserSessionId, target -> handoffs.cancel(target, reason), false);
    }

    private Map<String, Object> resolveHandoff(String sessionId, String browserSessionId, Predicate<String> resolver,
        boolean unpauseOnUnresolved) {
        if (handoffs == null) {
            setPaused(sessionId, false);
            return Map.of("ok", true, "resolved", false, "handoffs", List.of());
        }
        String target = resolveTargetBrowserSession(sessionId, browserSessionId);
        if (target == null) {
            return Map.of("ok", false, "error", "目标浏览器通道不属于当前会话");
        }
        boolean resolved = resolver.test(target);
        if (unpauseOnUnresolved && !resolved && target.equals(sessionId)) {
            setPaused(sessionId, false);
        }
        return Map.of("ok", true, "resolved", resolved, "handoffs", handoffs.pending(sessionId));
    }

    public Map<String, Object> evidence(String sessionId, String runId, int limit) {
        if (evidence == null) {
            return Map.of("items", List.of(), "summary", Map.of("total", 0));
        }
        List<BrowserEvidenceRecord> items = evidence.list(sessionId, runId, limit);
        Map<String, Object> summary = evidence.summary(sessionId, runId);
        return Map.of("items", items, "summary", summary);
    }

    public Optional<Path> resolveEvidenceImage(String sessionId, String evidenceId) {
        return evidence == null ? Optional.empty() : evidence.resolveScreenshot(sessionId, evidenceId);
    }

    public Optional<Path> resolveEvidenceRecording(String sessionId, String evidenceId) {
        return evidence == null ? Optional.empty() : evidence.resolveRecording(sessionId, evidenceId);
    }

    public Map<String, Object> userAction(String sessionId, String op, Map<String, Object> args) {
        return userAction(sessionId, null, op, args);
    }

    public Map<String, Object> userAction(String sessionId, String targetBrowserSessionId, String op,
        Map<String, Object> args) {
        String target = resolveTargetBrowserSession(sessionId, targetBrowserSessionId);
        if (target == null) {
            return Map.of("ok", false, "error", "目标浏览器通道不属于当前会话");
        }
        Map<String, Object> command = new LinkedHashMap<>();
        if (args != null) {
            command.putAll(args);
        }
        // 只由用户面板入口设置；模型 BrowserTool 不转发此字段。
        command.put("op", op);
        command.put("userInitiated", true);
        try {
            BrowserBackend.Reply reply = runtime.execute(target, command, USER_COMMAND_TIMEOUT);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("text", reply.text() == null ? "" : reply.text());
            result.put("browserSessionId", target);
            return result;
        } catch (RuntimeException e) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("ok", false);
            failure.put("error", e.getMessage() == null ? "浏览器操作失败" : e.getMessage());
            failure.put("browserSessionId", target);
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
        String activeLane = activeLaneOf(sessionId);
        controls.execute(() -> {
            if (paused) {
                runtime.clearOverlay(activeLane);
            } else {
                runtime.setAgentControl(activeLane, false, "", true);
            }
        });
        State state = states.get(sessionId);
        if (state != null) {
            broadcast(state, "state", statePayload(sessionId));
        }
    }

    /** 立即终止智能体控制锁定、移除页面蓝框并暂停后续交互动作，交由用户接管。 */
    public Map<String, Object> stopControl(String sessionId, String targetBrowserSessionId) {
        String target = resolveTargetBrowserSession(sessionId, targetBrowserSessionId);
        if (target == null) {
            return Map.of("ok", false, "error", "目标浏览器通道不属于当前会话");
        }
        runtime.stopControl(target);
        State state = states.get(sessionId);
        if (state != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", "control-stopped");
            body.put("session", target);
            body.put("browserSessionId", target);
            body.put("agentControlled", false);
            body.put("controlStatus", "");
            broadcast(state, "control-stopped", mapper.writeValueAsString(body));
            broadcast(state, "state", statePayload(sessionId));
        }
        return Map.of("ok", true, "browserSessionId", target, "paused", true, "agentControlled", false);
    }

    @Override
    public void onEvent(String sessionId, String kind, JsonNode payload) {
        String parentId = "browser-closed".equals(kind) ? "" : runtime.parentSessionOf(sessionId);
        if (parentId == null || parentId.isBlank()) {
            parentId = sessionId;
        }
        switch (kind) {
            case "mirror-frame" -> {
                State state = states.get(parentId);
                if (state == null) {
                    return;
                }
                synchronized (state) {
                    if (state.capturing()) {
                        state.laneFrames.put(sessionId, payload);
                        if (sessionId.equals(state.activeLane)) {
                            state.frame = payload; // latest-wins；无画面消费者时不保留帧。
                            broadcast(state, "frame", payload.toString());
                        }
                    }
                }
            }
            case "agent-action" -> {
                JsonNode enriched = withBrowserSessionId(payload, sessionId);
                State state = states.get(parentId);
                if (state != null) {
                    synchronized (state) {
                        state.laneActions.put(sessionId, enriched);
                        broadcast(state, "action", enriched.toString());
                    }
                }
            }
            case "control-state" -> {
                boolean active = payload.path("agentControlled").asBoolean(false);
                String controlStatus = payload.path("controlStatus").asString("");
                runtime.recordControlState(sessionId, active, controlStatus);
                JsonNode enriched = withBrowserSessionId(payload, sessionId);
                State state = states.get(parentId);
                if (state != null) {
                    broadcast(state, "control-state", enriched.toString());
                    broadcast(state, "state", statePayload(parentId));
                }
            }
            case "control-stopped" -> {
                runtime.recordControlState(sessionId, false, "");
                runtime.setInteractionsPaused(sessionId, true);
                JsonNode enriched = withBrowserSessionId(payload, sessionId);
                State state = states.get(parentId);
                if (state != null) {
                    broadcast(state, "control-stopped", enriched.toString());
                    broadcast(state, "state", statePayload(parentId));
                }
            }
            case "nav" -> {
                JsonNode enriched = withBrowserSessionId(payload, sessionId);
                State state = states.get(parentId);
                if (state != null) {
                    synchronized (state) {
                        state.laneNavs.put(sessionId, enriched);
                        if (sessionId.equals(state.activeLane)) {
                            state.navigation = enriched;
                        }
                        broadcast(state, "nav", enriched.toString());
                    }
                }
            }
            case "browser-closed" -> {
                // 进程级事件：浏览器断了（空闲回收/崩溃/用户手关），提示所有会话并清掉缓存帧。
                for (State state : states.values()) {
                    synchronized (state) {
                        state.frame = null;
                        state.navigation = null;
                        state.laneFrames.clear();
                        state.laneNavs.clear();
                        state.laneActions.clear();
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
        synchronized (sendLock(emitter)) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException error) {
                // ClientAbortException 等：对端已断，静默收尾并摘掉订阅
                drop(state, emitter);
            }
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
        // 订阅断开不代表用户完成接管；仍有待处理 handoff 时保留暂停状态。
        boolean pendingHandoff = handoffs != null && !handoffs.pending(state.id).isEmpty();
        boolean userTakeover = runtime.interactionsPaused(state.id);
        if (!pendingHandoff && !userTakeover) {
            runtime.setInteractionsPaused(state.id, false);
        }
        state.frame = null;
        state.laneFrames.clear();
        String active = state.activeLane;
        controls.execute(() -> runtime.mirrorControl(active, false, maxWidth, quality));
        // 先排入旧连接的停止命令，再允许新 state 被创建，避免旧停止命令覆盖新订阅。
        states.remove(state.id, state);
    }

    /** 必须持有 state 锁，保证订阅切换和控制命令的顺序一致。 */
    private void controlCapture(State state) {
        boolean capture = state.capturing();
        String target = state.activeLane;
        if (!capture) {
            state.frame = null;
            state.laneFrames.remove(target);
        }
        controls.execute(() -> {
            if (capture) {
                runtime.mirrorControl(target, true, maxWidth, quality);
            } else {
                runtime.mirrorCapture(target, false, maxWidth, quality);
            }
        });
    }

    private void onHandoffEvent(BrowserHandoffBroker.PendingHandoffEvent event) {
        BrowserHandoffBroker.PendingHandoff handoff = event.handoff();
        String sessionId = handoff.sessionId();
        String target = handoff.browserSessionId();
        if (event.eventKind() == BrowserHandoffBroker.EventKind.REQUESTED) {
            runtime.setInteractionsPaused(target, true);
            controls.execute(() -> runtime.clearOverlay(target));
            State state = states.get(sessionId);
            if (state != null) {
                synchronized (state) {
                    if (!target.equals(state.activeLane)) {
                        String previous = state.activeLane;
                        state.activeLane = target;
                        switchMirrorLane(state, previous, target);
                    }
                }
            }
        } else {
            if (handoffs == null || handoffs.pendingForBrowser(target).isEmpty()) {
                runtime.setInteractionsPaused(target, false);
                boolean resume = event.eventKind() == BrowserHandoffBroker.EventKind.RESUMED;
                controls.execute(() -> runtime.setAgentControl(target, false, "", resume));
            }
        }
        State state = states.get(sessionId);
        if (state != null) {
            String eventType = switch (event.eventKind()) {
                case REQUESTED -> "requested";
                case RESUMED -> "resumed";
                case CANCELLED -> "cancelled";
            };
            broadcast(state, "handoff", handoffSnapshotJson(sessionId, eventType, handoff, event.note()));
            broadcast(state, "state", statePayload(sessionId));
        }
    }

    private void onEvidenceRecord(BrowserEvidenceRecord record) {
        State state = states.get(record.sessionId());
        if (state != null) {
            broadcast(state, "evidence", mapper.writeValueAsString(record));
        }
    }

    private String resolveTargetBrowserSession(String sessionId, String requestedBrowserSessionId) {
        if (requestedBrowserSessionId == null || requestedBrowserSessionId.isBlank()) {
            return activeLaneOf(sessionId);
        }
        String candidate = requestedBrowserSessionId.strip();
        if (candidate.equals(sessionId) || sessionId.equals(runtime.parentSessionOf(candidate))) {
            return candidate;
        }
        return null;
    }

    private String activeLaneOf(String sessionId) {
        State state = states.get(sessionId);
        if (state == null) {
            return sessionId;
        }
        synchronized (state) {
            return state.activeLane == null || state.activeLane.isBlank() ? sessionId : state.activeLane;
        }
    }

    private JsonNode withBrowserSessionId(JsonNode payload, String browserSessionId) {
        if (payload instanceof ObjectNode object && !object.has("browserSessionId")) {
            ObjectNode copy = object.deepCopy();
            copy.put("browserSessionId", browserSessionId);
            return copy;
        }
        return payload;
    }

    private String handoffSnapshotJson(String sessionId, String eventType, BrowserHandoffBroker.PendingHandoff handoff,
        String note) {
        List<BrowserHandoffBroker.PendingHandoff> pending = handoffs == null ? List.of() : handoffs.pending(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", eventType);
        body.put("handoff", handoff);
        body.put("note", note == null ? "" : note);
        body.put("pending", pending);
        return mapper.writeValueAsString(body);
    }

    private void switchMirrorLane(State state, String previous, String target) {
        if (!previous.equals(target) && state.capturing()) {
            controls.execute(() -> {
                runtime.mirrorControl(previous, false, maxWidth, quality);
                runtime.mirrorControl(target, true, maxWidth, quality);
            });
        }
    }

    private Map<String, Object> baseStateMap(String sessionId, Availability availability) {
        String activeLane = activeLaneOf(sessionId);
        boolean controlled = runtime.agentControlled(activeLane)
            || (!activeLane.equals(sessionId) && runtime.agentControlled(sessionId));
        List<BrowserHandoffBroker.PendingHandoff> pending = handoffs == null ? List.of() : handoffs.pending(sessionId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("paused", runtime.interactionsPaused(sessionId) || runtime.interactionsPaused(activeLane));
        map.put("agentControlled", controlled);
        map.put("controlStatus", currentControlStatus(sessionId, activeLane));
        map.put("available", availability.available());
        map.put("headless", runtime.headless());
        map.put("activeLane", activeLane);
        map.put("lanes", runtime.activeLanes(sessionId));
        map.put("handoffs", pending);
        return map;
    }

    private String statePayload(String sessionId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sessionId", sessionId);
        state.putAll(baseStateMap(sessionId, runtime.availability()));
        if (evidence != null) {
            state.put("evidenceCount",
                evidence.list(sessionId, "", BrowserEvidenceService.MAX_RECORDS_PER_SESSION).size());
        }
        return mapper.writeValueAsString(state);
    }

    private String currentControlStatus(String sessionId, String activeLane) {
        String activeStatus = runtime.agentControlStatus(activeLane);
        if (activeStatus != null && !activeStatus.isEmpty()) {
            return activeStatus;
        }
        String parentStatus = activeLane.equals(sessionId) ? "" : runtime.agentControlStatus(sessionId);
        return parentStatus == null ? "" : parentStatus;
    }

    /** 每个会话的镜像状态：最新一帧 + 订阅者表 + 子泳道缓存。 */
    private static final class State {
        final String id;
        final Map<SseEmitter, Boolean> subscribers = new LinkedHashMap<>();
        final Map<String, JsonNode> laneFrames = new LinkedHashMap<>();
        final Map<String, JsonNode> laneNavs = new LinkedHashMap<>();
        final Map<String, JsonNode> laneActions = new LinkedHashMap<>();
        String activeLane;
        JsonNode frame;
        JsonNode navigation;
        boolean captureEnabled = true;

        boolean capturing() {
            return captureEnabled && subscribers.containsValue(true);
        }

        State(String id) {
            this.id = id;
            this.activeLane = id;
        }
    }
}
