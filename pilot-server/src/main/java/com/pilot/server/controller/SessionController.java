package com.pilot.server.controller;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.session.EventSubscription;
import com.pilot.core.session.Run;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.session.RunStarter;
import com.pilot.server.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private static final String DEFAULT_TITLE = "未命名会话";

    private final SessionService sessions;
    private final RunStarter runStarter;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaces;
    private final AgentProperties agentProperties;

    public SessionController(SessionService sessions, RunStarter runStarter, ObjectMapper objectMapper,
                             WorkspaceService workspaces, AgentProperties agentProperties) {
        this.sessions = sessions;
        this.runStarter = runStarter;
        this.objectMapper = objectMapper;
        this.workspaces = workspaces;
        this.agentProperties = agentProperties;
    }

    @PostMapping
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null || request.title() == null || request.title().isBlank()
                ? DEFAULT_TITLE
                : request.title();
        Session session = sessions.createSession(title, workspaceOf(request));
        return SessionResponse.of(session, 0);
    }

    /** 未指定工作区时沿用服务端默认值，保持既有调用方式可用。 */
    private String workspaceOf(CreateSessionRequest request) {
        String raw = request == null ? null : request.workspace();
        if (raw == null || raw.isBlank()) {
            return agentProperties.workspace();
        }
        try {
            return workspaces.requireDirectory(raw).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 会话列表（按创建时间倒序），供侧栏展示。eventCount 只在单条查询时统计。 */
    @GetMapping
    public List<SessionResponse> list() {
        return sessions.list().stream().map(session -> SessionResponse.of(session, 0)).toList();
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable String id) {
        Session session = requireSession(id);
        return SessionResponse.of(session, sessions.events(id).size());
    }

    @PatchMapping("/{id}")
    public SessionResponse rename(@PathVariable String id, @RequestBody(required = false) RenameRequest request) {
        requireSession(id);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        sessions.rename(id, request.title());
        return SessionResponse.of(requireSession(id), sessions.events(id).size());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!sessions.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    /** 断线重连后校准界面：告诉前端该会话是否仍有运行在进行。 */
    @GetMapping("/{id}/runs/active")
    public ActiveRunResponse activeRun(@PathVariable String id) {
        requireSession(id);
        return new ActiveRunResponse(id, runStarter.activeRun(id).orElse(null));
    }

    /** 用上一次的用户输入重跑一轮，历史里不会重复这条用户消息。 */
    @PostMapping("/{id}/regenerate")
    public MessageResponse regenerate(@PathVariable String id) {
        requireSession(id);
        String input = lastUserInput(id);
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可重新生成的输入");
        }
        String runId = "run_" + UUID.randomUUID();
        sessions.publish(id, runId, new RunStarted(input));
        runStarter.regenerate(id, runId, input);
        return new MessageResponse(id, runId);
    }

    private String lastUserInput(String sessionId) {
        List<EventEnvelope> events = sessions.events(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).payload() instanceof RunStarted started) {
                return started.input();
            }
        }
        return null;
    }

    @PostMapping("/{id}/messages")
    public MessageResponse sendMessage(@PathVariable String id, @RequestBody MessageRequest request) {
        requireSession(id);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        Run run = sessions.sendMessage(id, request.content());
        runStarter.start(id, run.runId(), request.content());
        return new MessageResponse(id, run.runId());
    }

    /**
     * SSE 事件流（服务端 → 客户端单向推送）。
     * 支持 {@code Last-Event-ID} 请求头或 {@code after} 查询参数作为游标，用于断线重连。
     */
    /**
     * 历史事件分页：界面首屏只取尾部一页，避免长会话一次性回放上千条事件。
     *
     * @param before 只返回序号小于它的历史，便于向上翻页
     */
    @GetMapping("/{id}/history")
    public HistoryResponse history(@PathVariable String id,
                                   @RequestParam(name = "limit", defaultValue = "60") int limit,
                                   @RequestParam(name = "before", required = false) Long before) {
        requireSession(id);
        int capped = Math.max(1, Math.min(limit, 500));
        List<EventEnvelope> all = sessions.events(id);
        int end = all.size();
        if (before != null) {
            end = 0;
            while (end < all.size() && all.get(end).sequence() < before) {
                end++;
            }
        }
        int start = Math.max(0, end - capped);
        return new HistoryResponse(List.copyOf(all.subList(start, end)), start > 0, all.size());
    }

    /**
     * @param hasMore 是否还有更早的事件
     * @param total   该会话的事件总数
     */
    public record HistoryResponse(List<EventEnvelope> events, boolean hasMore, int total) { }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id,
                                             @RequestParam(name = "after", required = false) Long after,
                                             @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        requireSession(id);
        long cursor = after != null ? after : lastEventId != null ? lastEventId : 0L;
        var emitter = new SseEmitter(0L);
        var closed = new AtomicBoolean(false);
        var subscription = new AtomicReference<EventSubscription>();

        // 浏览器刷新、切换会话或关页面都会让连接失效；这属于正常生命周期，
        // 不能把写入失败当成异常抛给上层，否则每个事件都会刷一份堆栈。
        Consumer<EventEnvelope> listener = envelope -> {
            if (closed.get()) {
                return;
            }
            if (!send(emitter, envelope)) {
                closeQuietly(closed, subscription, emitter);
            }
        };

        try {
            emitter.send(SseEmitter.event().comment("stream open: session=" + id + ", after=" + cursor));
            EventSubscription created = sessions.subscribe(id, cursor, listener);
            subscription.set(created);
            if (closed.get()) {
                created.close();
            }
            emitter.onCompletion(() -> closeQuietly(closed, subscription, null));
            emitter.onTimeout(() -> closeQuietly(closed, subscription, null));
            emitter.onError(error -> closeQuietly(closed, subscription, null));
        } catch (IOException | RuntimeException e) {
            emitter.completeWithError(e);
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** @return {@code false} 表示客户端已经断开，调用方应停止推送并注销订阅 */
    private boolean send(SseEmitter emitter, EventEnvelope envelope) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(envelope.sequence()))
                    .name(envelope.type())
                    .data(objectMapper.writeValueAsString(envelope)));
            return true;
        } catch (IOException | IllegalStateException e) {
            // ClientAbortException / AsyncRequestNotUsableException：对端已关闭，静默收尾
            return false;
        }
    }

    private static void closeQuietly(AtomicBoolean closed, AtomicReference<EventSubscription> subscription,
                                     SseEmitter emitter) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EventSubscription current = subscription.get();
        if (current != null) {
            current.close();
        }
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 连接已经不可用，complete 失败无需处理
            }
        }
    }

    private Session requireSession(String id) {
        return sessions.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
    }

    public record CreateSessionRequest(String title, String workspace) { }

    public record RenameRequest(String title) { }

    public record MessageRequest(String content) { }

    public record MessageResponse(String sessionId, String runId) { }

    public record ActiveRunResponse(String sessionId, String runId) { }

    public record SessionResponse(String sessionId, String title, Instant createdAt, SessionStatus status,
                                  String workspace, int eventCount) {
        static SessionResponse of(Session session, int eventCount) {
            return new SessionResponse(session.id(), session.title(), session.createdAt(), session.status(),
                    session.workspace(), eventCount);
        }
    }
}
