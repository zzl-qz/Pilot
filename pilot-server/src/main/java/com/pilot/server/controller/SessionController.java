package com.pilot.server.controller;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.session.EventSubscription;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.Run;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.session.AttachmentService;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final PlanRepository plans;
    private final ConversationRepository conversations;
    private final AttachmentService attachments;

    public SessionController(SessionService sessions, RunStarter runStarter, ObjectMapper objectMapper,
                             WorkspaceService workspaces, AgentProperties agentProperties,
                             PlanRepository plans, ConversationRepository conversations,
                             AttachmentService attachments) {
        this.sessions = sessions;
        this.runStarter = runStarter;
        this.attachments = attachments;
        this.objectMapper = objectMapper;
        this.workspaces = workspaces;
        this.agentProperties = agentProperties;
        this.plans = plans;
        this.conversations = conversations;
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
        conversations.delete(id);
        plans.delete(id);
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

    /**
     * 继续上一次被中断的运行：不追加用户消息，直接恢复已落盘的对话记忆。
     * 与 regenerate 的区别是不改写历史语义。
     */
    @PostMapping("/{id}/continue")
    public MessageResponse continueRun(@PathVariable String id) {
        requireSession(id);
        if (runStarter.activeRun(id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话仍有运行在进行");
        }
        if (!runStarter.resumable(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可继续的对话记忆");
        }
        String runId = "run_" + UUID.randomUUID();
        sessions.publish(id, runId, new RunStarted("（继续上一次未完成的任务）"));
        runStarter.resume(id, runId);
        return new MessageResponse(id, runId);
    }

    /** 还没被当前运行消费的消息。空着表示没有在排队。 */
    @GetMapping("/{id}/queue")
    public QueueResponse queue(@PathVariable String id) {
        requireSession(id);
        return new QueueResponse(runStarter.queued(id));
    }

    /** 改队列里的某一句，下标从 0 开始。还没轮到的才能改。 */
    @PutMapping("/{id}/queue/{index}")
    public QueueResponse editQueued(@PathVariable String id, @PathVariable int index,
                                    @RequestBody(required = false) QueueEditRequest request) {
        requireSession(id);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        if (!runStarter.editQueued(id, index, request.content())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "队列里没有这一句");
        }
        return new QueueResponse(runStarter.queued(id));
    }

    @DeleteMapping("/{id}/queue/{index}")
    public QueueResponse removeQueued(@PathVariable String id, @PathVariable int index) {
        requireSession(id);
        if (!runStarter.removeQueued(id, index)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "队列里没有这一句");
        }
        return new QueueResponse(runStarter.queued(id));
    }

    /**
     * 改已经发出去的一句并重跑。这句以及它后面的回复都会拿掉，再用新内容开一轮。
     * 运行中不能改，免得和正在跑的那轮打架；还没轮到的话去队列里改。
     */
    @PostMapping("/{id}/resend")
    public MessageResponse resend(@PathVariable String id, @RequestBody(required = false) ResendRequest request) {
        requireSession(id);
        if (request == null || request.sequence() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sequence must not be null");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        if (runStarter.activeRun(id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "运行中不能改已经发出的消息");
        }
        List<EventEnvelope> events = sessions.events(id);
        String original = null;
        for (EventEnvelope event : events) {
            if (event.sequence() == request.sequence() && event.payload() instanceof RunStarted started) {
                original = started.input();
                break;
            }
        }
        if (original == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能改你发出去的那一句");
        }
        int occurrence = 0;
        for (EventEnvelope event : events) {
            if (event.sequence() > request.sequence()) {
                break;
            }
            if (event.payload() instanceof RunStarted started && original.equals(started.input())) {
                occurrence++;
            }
        }
        String content = request.content().strip();
        runStarter.rewindBeforeUserInput(id, occurrence, original);
        sessions.dropEventsFrom(id, request.sequence());
        String runId = "run_" + UUID.randomUUID();
        sessions.publish(id, runId, new RunStarted(content));
        runStarter.start(id, runId, content);
        return new MessageResponse(id, runId);
    }

    /** 当前计划清单：前端刷新后据此恢复 checklist。 */
    @GetMapping("/{id}/plan")
    public PlanResponse plan(@PathVariable String id) {
        requireSession(id);
        return new PlanResponse(plans.load(id));
    }

    public record PlanResponse(List<PlanItem> plan) {
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
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable String id, @RequestBody MessageRequest request) {
        requireSession(id);
        boolean hasAttachments = request != null && request.attachments() != null && !request.attachments().isEmpty();
        if (request == null || ((request.content() == null || request.content().isBlank()) && !hasAttachments)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        // 运行中只接受纯文本追加；先检查状态，避免被拒绝的附件请求产生文件副作用。
        String activeRun = runStarter.activeRun(id).orElse(null);
        if (activeRun != null && hasAttachments) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "运行中不能追加附件，请等本轮结束后再发");
        }
        AttachmentService.Prepared prepared;
        try {
            prepared = attachments.prepare(id, request.content(), request.attachments());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (activeRun != null) {
            if (runStarter.steer(id, prepared.content())) {
                return ResponseEntity.accepted().body(new MessageResponse(id, activeRun));
            }
        }
        Run run = sessions.sendMessage(id, prepared.content());
        runStarter.start(id, run.runId(), prepared.content(), prepared.attachments());
        return ResponseEntity.ok().body(new MessageResponse(id, run.runId()));
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

    public record MessageRequest(String content, List<AttachmentService.Inbound> attachments) { }

    public record ResendRequest(Long sequence, String content) { }

    public record QueueEditRequest(String content) { }

    public record QueueResponse(List<String> items) { }

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
