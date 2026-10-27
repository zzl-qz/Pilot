package com.pilot.server.controller;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.project.Project;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.EventSubscription;
import com.pilot.core.session.PlanRepository;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private static final String DEFAULT_TITLE = "未命名会话";
    /**
     * SSE 心跳：空闲连接也要定期写一点东西，否则对端早已断开时服务端感知不到，
     * 订阅者会一直挂在内存里。单线程足够，任务本身只是写一个注释帧。
     */
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pilot-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private static final long HEARTBEAT_SECONDS = 20L;

    private final SessionService sessions;
    private final RunStarter runStarter;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaces;
    private final AgentProperties agentProperties;
    private final PlanRepository plans;
    private final AttachmentService attachments;
    private final ProjectRepository projects;
    private final com.pilot.server.session.SessionCleanupService cleanup;

    public SessionController(SessionService sessions, RunStarter runStarter, ObjectMapper objectMapper,
                             WorkspaceService workspaces, AgentProperties agentProperties,
                             PlanRepository plans, AttachmentService attachments,
                             ProjectRepository projects,
                             com.pilot.server.session.SessionCleanupService cleanup) {
        this.sessions = sessions;
        this.runStarter = runStarter;
        this.attachments = attachments;
        this.objectMapper = objectMapper;
        this.workspaces = workspaces;
        this.agentProperties = agentProperties;
        this.plans = plans;
        this.projects = projects;
        this.cleanup = cleanup;
    }

    /**
     * 在项目下新建对话。
     *
     * <p>同一项目最多只有一条「未归档的空白对话」。已经有的话返回 409，
     * 由界面问用户「归档旧的再新建」还是「回去用旧的」——不静默复用，
     * 也不允许把空白对话堆起来（用户看不见的会话最难收拾）。
     *
     * @param replaceBlank 为 true 时表示用户已确认「归档旧空白对话并新建」
     */
    @PostMapping
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null || request.title() == null || request.title().isBlank()
                ? DEFAULT_TITLE
                : request.title();
        String projectId = request == null ? null : blankToNull(request.projectId());
        Project project = projectId == null ? null : requireProject(projectId);

        if (project != null) {
            var existingBlank = sessions.findBlank(projectId);
            if (existingBlank.isPresent()) {
                if (request == null || request.replaceBlank() == null || !request.replaceBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "该项目已有未使用的空白对话（" + existingBlank.get().id() + "），"
                                    + "请先归档它再新建，或者直接回到那条对话。");
                }
                sessions.setArchived(existingBlank.get().id(), true);
            }
        }
        Session session = sessions.createSession(title, workspaceOf(request, project), projectId, true);
        return SessionResponse.of(session, 0);
    }

    /** 归档 / 取消归档：归档的对话不进主列表，但历史与事件都保留。 */
    @PatchMapping("/{id}/archive")
    public SessionResponse archive(@PathVariable String id, @RequestBody(required = false) ArchiveRequest request) {
        requireSession(id);
        boolean archived = request == null || request.archived() == null || request.archived();
        sessions.setArchived(id, archived);
        return SessionResponse.of(requireSession(id), countOf(id));
    }

    public record ArchiveRequest(Boolean archived) { }

    /**
     * 决定新对话的工作区。
     *
     * <p>属于项目时只能选项目绑定的文件夹（不传就用第一个），避免「项目绑 A、对话跑在 B」
     * 这种在界面上看不出来的错位；不属于项目时保持旧行为，可以指定任意存在的目录。
     */
    private String workspaceOf(CreateSessionRequest request, Project project) {
        String raw = request == null ? null : blankToNull(request.workspace());
        if (project != null) {
            String folder = raw == null ? project.primaryFolder() : insideProject(project, raw);
            if (folder == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目还没有绑定文件夹，请先在项目设置里添加目录");
            }
            return folder;
        }
        if (raw == null) {
            return agentProperties.workspace();
        }
        try {
            return workspaces.requireDirectory(raw).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 把路径规范化为项目里登记的写法；不属于该项目时 400。 */
    private String insideProject(Project project, String raw) {
        java.nio.file.Path candidate;
        try {
            candidate = java.nio.file.Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径不合法: " + raw);
        }
        for (String folder : project.folders()) {
            if (java.nio.file.Path.of(folder).toAbsolutePath().normalize().equals(candidate)) {
                return folder;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该目录不在项目绑定的文件夹里: " + raw);
    }

    private Project requireProject(String projectId) {
        return projects.find(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found: " + projectId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 会话列表（按创建时间倒序），供侧栏展示。
     *
     * @param projectId 只看某个项目；传 {@code none} 表示未分组的历史会话；不传表示全部
     */
    @GetMapping
    public List<SessionResponse> list(@RequestParam(name = "projectId", required = false) String projectId) {
        List<Session> source;
        if (projectId == null) {
            source = sessions.list();
        } else if ("none".equals(projectId)) {
            source = sessions.listByProject(null);
        } else {
            source = sessions.listByProject(projectId);
        }
        return source.stream().map(session -> SessionResponse.of(session, 0)).toList();
    }

    /**
     * 切换对话当前使用的文件夹：只允许项目绑定的目录。
     * 下一次运行才会用新目录，进行中的运行不受影响。
     */
    @PatchMapping("/{id}/workspace")
    public SessionResponse switchWorkspace(@PathVariable String id,
                                           @RequestBody(required = false) WorkspaceRequest request) {
        Session session = requireSession(id);
        if (request == null || request.workspace() == null || request.workspace().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace must not be blank");
        }
        String target;
        if (session.projectId() == null) {
            try {
                target = workspaces.requireDirectory(request.workspace()).toString();
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        } else {
            target = insideProject(requireProject(session.projectId()), request.workspace());
        }
        sessions.moveToWorkspace(id, target);
        return SessionResponse.of(requireSession(id), countOf(id));
    }

    public record WorkspaceRequest(String workspace) { }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable String id) {
        Session session = requireSession(id);
        return SessionResponse.of(session, countOf(id));
    }

    @PatchMapping("/{id}")
    public SessionResponse rename(@PathVariable String id, @RequestBody(required = false) RenameRequest request) {
        requireSession(id);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        sessions.rename(id, request.title());
        return SessionResponse.of(requireSession(id), countOf(id));
    }

    /** 事件总数走存储层计数，不把整表事件读进内存再 size()。 */
    private int countOf(String sessionId) {
        long total = sessions.eventCount(sessionId);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!cleanup.delete(id)) {
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
        String runId = claimRun(id);
        launch(runId, () -> {
            sessions.publish(id, runId, new RunStarted(input));
            runStarter.regenerate(id, runId, input);
        });
        return new MessageResponse(id, runId);
    }

    /**
     * 继续上一次被中断的运行：不追加用户消息，直接恢复已落盘的对话记忆。
     * 与 regenerate 的区别是不改写历史语义。
     */
    @PostMapping("/{id}/continue")
    public MessageResponse continueRun(@PathVariable String id) {
        requireSession(id);
        if (!runStarter.resumable(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可继续的对话记忆");
        }
        String runId = claimRun(id);
        launch(runId, () -> {
            sessions.publish(id, runId, new RunStarted("（继续上一次未完成的任务）"));
            runStarter.resume(id, runId);
        });
        return new MessageResponse(id, runId);
    }

    /**
     * 抢占运行槽位。检查「有没有在跑」和真正启动之间不再是两步无锁操作，
     * 并发请求只有一个能拿到槽位，其余返回 409。
     */
    private String claimRun(String sessionId) {
        return runStarter.claimRun(sessionId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "该会话仍有运行在进行"));
    }

    /** 启动失败（发布事件或注册运行抛异常）时把槽位还回去，避免会话被永久占住。 */
    private void launch(String runId, Runnable starter) {
        try {
            starter.run();
        } catch (RuntimeException e) {
            runStarter.abandon(runId);
            throw e;
        }
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
        String originalInput = original;
        int originalOccurrence = occurrence;
        String runId = claimRun(id);
        launch(runId, () -> {
            runStarter.rewindBeforeUserInput(id, originalOccurrence, originalInput);
            sessions.dropEventsFrom(id, request.sequence());
            sessions.publish(id, runId, new RunStarted(content));
            runStarter.start(id, runId, content);
        });
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
        // 检查与启动之间可能刚好起了一个 run：抢占失败就退化成追加输入
        String runId = runStarter.claimRun(id).orElse(null);
        if (runId == null) {
            if (runStarter.steer(id, prepared.content())) {
                return ResponseEntity.accepted()
                        .body(new MessageResponse(id, runStarter.activeRun(id).orElse(null)));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话仍有运行在进行");
        }
        launch(runId, () -> {
            sessions.publish(id, runId, new RunStarted(prepared.content()));
            runStarter.start(id, runId, prepared.content(), prepared.attachments());
        });
        return ResponseEntity.ok().body(new MessageResponse(id, runId));
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
        List<EventEnvelope> page = sessions.eventsPage(id, before, capped);
        boolean hasMore = !page.isEmpty() && page.get(0).sequence() > 1;
        return new HistoryResponse(page, hasMore, countOf(id));
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
        var heartbeat = new AtomicReference<ScheduledFuture<?>>();

        // 浏览器刷新、切换会话或关页面都会让连接失效；这属于正常生命周期，
        // 不能把写入失败当成异常抛给上层，否则每个事件都会刷一份堆栈。
        Consumer<EventEnvelope> listener = envelope -> {
            if (closed.get()) {
                return;
            }
            if (!send(emitter, envelope)) {
                closeQuietly(closed, subscription, heartbeat, emitter);
            }
        };

        try {
            emitter.send(SseEmitter.event().comment("stream open: session=" + id + ", after=" + cursor));
            // 第四个参数：订阅被服务端摘掉（客户端积压太多）时，把底层连接一起关掉
            subscription.set(sessions.subscribe(id, cursor, listener,
                    () -> closeQuietly(closed, subscription, heartbeat, emitter)));
            if (closed.get()) {
                subscription.get().close();
            }
            emitter.onCompletion(() -> closeQuietly(closed, subscription, heartbeat, null));
            emitter.onTimeout(() -> closeQuietly(closed, subscription, heartbeat, null));
            emitter.onError(error -> closeQuietly(closed, subscription, heartbeat, null));
            // 空闲连接也定期写注释帧：对端早已断开时，下一次写失败就能把订阅清掉
            heartbeat.set(HEARTBEAT.scheduleAtFixedRate(() -> {
                if (closed.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    closeQuietly(closed, subscription, heartbeat, emitter);
                }
            }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS));
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
                                     AtomicReference<ScheduledFuture<?>> heartbeat, SseEmitter emitter) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> beat = heartbeat.get();
        if (beat != null) {
            beat.cancel(false);
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

    public record CreateSessionRequest(String title, String workspace, String projectId, Boolean replaceBlank) { }

    public record RenameRequest(String title) { }

    public record MessageRequest(String content, List<AttachmentService.Inbound> attachments) { }

    public record ResendRequest(Long sequence, String content) { }

    public record QueueEditRequest(String content) { }

    public record QueueResponse(List<String> items) { }

    public record MessageResponse(String sessionId, String runId) { }

    public record ActiveRunResponse(String sessionId, String runId) { }

    public record SessionResponse(String sessionId, String title, Instant createdAt, SessionStatus status,
                                  String workspace, String projectId, boolean isBlank, boolean archived,
                                  int eventCount) {
        static SessionResponse of(Session session, int eventCount) {
            return new SessionResponse(session.id(), session.title(), session.createdAt(), session.status(),
                    session.workspace(), session.projectId(), session.isBlank(), session.archived(), eventCount);
        }
    }
}
