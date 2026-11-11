package com.pilot.server.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.project.Project;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.EventSubscription;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.core.tool.DocumentRenderer;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.session.AttachmentReferences;
import com.pilot.server.session.AttachmentService;
import com.pilot.server.session.RunStarter;
import com.pilot.server.session.RunSubmissionService;
import com.pilot.server.session.SessionCleanupService;
import com.pilot.server.session.SkillService;
import com.pilot.server.session.TaskControlService;
import com.pilot.server.workspace.WorkspaceService;

import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private static final String DEFAULT_TITLE = "未命名会话";
    /**
     * SSE 心跳：空闲连接也要定期写一点东西，否则对端早已断开时服务端感知不到，
     * 订阅者会一直挂在内存里。每条连接独立发送，慢连接不阻塞其他连接的心跳。
     */
    private static final long HEARTBEAT_SECONDS = 20L;

    private final SessionService sessions;
    private final RunStarter runStarter;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaces;
    private final AgentProperties agentProperties;
    private final PlanRepository plans;
    private final AttachmentService attachments;
    private final AttachmentReferences attachmentReferences;
    private final ProjectRepository projects;
    private final SessionCleanupService cleanup;
    private final SkillService skills;
    private final ConversationRepository conversations;

    public SessionController(SessionService sessions, RunStarter runStarter, ObjectMapper objectMapper,
        WorkspaceService workspaces, AgentProperties agentProperties, PlanRepository plans,
        AttachmentService attachments, ProjectRepository projects, SessionCleanupService cleanup, SkillService skills,
        ConversationRepository conversations, AttachmentReferences attachmentReferences) {
        this.sessions = sessions;
        this.runStarter = runStarter;
        this.attachments = attachments;
        this.objectMapper = objectMapper;
        this.workspaces = workspaces;
        this.agentProperties = agentProperties;
        this.plans = plans;
        this.projects = projects;
        this.cleanup = cleanup;
        this.skills = skills;
        this.conversations = conversations;
        this.attachmentReferences = attachmentReferences;
    }

    /**
     * 在项目下新建对话。
     *
     * <p>同一项目最多只有一条「未归档的空白对话」。已经有的话返回 409，
     * 由界面问用户「归档旧的再新建」还是「回去用旧的」——不静默复用，
     * 也不允许把空白对话堆起来（用户看不见的会话最难收拾）。
     *
     * @param request 为 true 时表示用户已确认「归档旧空白对话并新建」
     */
    @PostMapping
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        String title =
            request == null || request.title() == null || request.title().isBlank() ? DEFAULT_TITLE : request.title();
        String projectId = request == null ? null : blankToNull(request.projectId());
        Project project = projectId == null ? null : requireProject(projectId);

        if (project != null) {
            Optional<Session> existingBlank = sessions.findBlank(projectId);
            if (existingBlank.isPresent()) {
                if (request == null || request.replaceBlank() == null || !request.replaceBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该项目已有未使用的空白对话（" + existingBlank.get().id() + "），" + "请先归档它再新建，或者直接回到那条对话。");
                }
                sessions.setArchived(existingBlank.get().id(), true);
            }
        }
        Session session = sessions.createSession(title, workspaceOf(request, project), projectId, true);
        if (request != null && request.title() != null && !request.title().isBlank()) {
            sessions.rename(session.id(), title);
            session = requireSession(session.id());
        }
        return SessionResponse.of(session, 0);
    }

    /**
     * 从当前对话分叉出一条临时侧边对话（对齐 Codex 的 /side）。
     *
     * <p>语义：拿到父对话的工作区、项目和模型记忆，但事件记录另起一份——
     * 面板里只显示侧边问答，主任务的工具流水不会混进来，主任务也不受影响。
     * 侧边对话不加入会话列表（见 {@link #list(String)}），关掉面板后由前端删除。
     *
     * <p>不允许在侧边对话里再开侧边对话：Codex 也是这么限制的，
     * 否则一个 /side 会无限套娃，用户很难回到主线。
     */
    @PostMapping("/{id}/side")
    public SessionResponse openSide(@PathVariable String id) {
        Session parent = requireSession(id);
        if (parent.isSide()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "侧边对话里不能再开侧边对话，请回到主对话");
        }
        Session side = sessions.createSession("侧边对话", parent.workspace(), parent.projectId(), false, parent.id());
        try {
            // 继承父对话的模型记忆：侧边问「这里为什么这么写」才有上下文可看
            conversations.save(side.id(), conversations.load(parent.id()));
        } catch (RuntimeException e) {
            cleanup.delete(side.id());
            throw e;
        }
        return SessionResponse.of(side, 0);
    }

    /** 归档 / 取消归档：归档的对话不进主列表，但历史与事件都保留。 */
    @PatchMapping("/{id}/archive")
    public SessionResponse archive(@PathVariable String id, @RequestBody(required = false) ArchiveRequest request) {
        requireSession(id);
        boolean archived = request == null || request.archived() == null || request.archived();
        sessions.setArchived(id, archived);
        return SessionResponse.of(requireSession(id), countOf(id));
    }

    public record ArchiveRequest(Boolean archived) {
    }

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
        Path candidate;
        try {
            candidate = Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径不合法: " + raw);
        }
        for (String folder : project.folders()) {
            if (Path.of(folder).toAbsolutePath().normalize().equals(candidate)) {
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
     * <p>侧边对话是主对话的临时分叉，不进主列表：否则关闭面板后它还会以一条「未分组」
     * 对话的身份留在侧栏里，用户也不知道那是什么。
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
        return source.stream().filter(session -> !session.isSide()).map(session -> SessionResponse.of(session, 0))
            .toList();
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

    public record WorkspaceRequest(String workspace) {
    }

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
        if (request.title().strip().codePointCount(0, request.title().strip().length()) > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能超过 120 个字符");
        }
        sessions.rename(id, request.title());
        return SessionResponse.of(requireSession(id), countOf(id));
    }

    @PatchMapping("/{id}/preferences")
    public SessionResponse preferences(@PathVariable String id, @RequestBody PreferencesRequest request) {
        requireSession(id);
        if (request == null || (request.pinned() == null && request.unread() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供置顶或未读状态");
        }
        sessions.updatePreferences(id, request.pinned(), request.unread());
        return SessionResponse.of(requireSession(id), countOf(id));
    }

    public record PreferencesRequest(Boolean pinned, Boolean unread) {
    }

    /** 事件总数走存储层计数，不把整表事件读进内存再 size()。 */
    private int countOf(String sessionId) {
        long total = sessions.eventCount(sessionId);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)total;
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

    /** Cursor/Codex 风格的上下文占用视图：读取的是模型记忆，不是聊天事件条数。 */
    @GetMapping("/{id}/context")
    public RunStarter.ContextStatus context(@PathVariable String id) {
        requireSession(id);
        return runStarter.contextStatus(id);
    }

    /** 主动压缩只改模型记忆；人可见的聊天与审计事件完整保留。 */
    @PostMapping("/{id}/context/compact")
    public RunStarter.CompactionResult compactContext(@PathVariable String id) {
        requireSession(id);
        try {
            return runStarter.compactContext(id);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
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
        return runStarter.claimRun(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "该会话仍有运行在进行"));
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
        return queueResponse(id);
    }

    private QueueResponse queueResponse(String id) {
        List<RunStarter.QueuedMessage> messages = runStarter.queuedMessages(id);
        return new QueueResponse(messages.stream().map(RunStarter.QueuedMessage::content).toList(),
            messages.stream()
                .map(message -> new QueueEntry(message.content(),
                    attachments.displayAttachments(id, message.attachments()), message.imagePositions()))
                .toList(),
            RunStarter.queueRevision(messages));
    }

    /** 取回当前会话上传的附件；二进制文件用 attachment disposition 下载。 */
    @GetMapping("/{id}/attachments/{file}")
    public ResponseEntity<Resource> attachment(@PathVariable String id, @PathVariable String file,
        @RequestParam(name = "name", required = false) String originalName) {
        requireSession(id);
        Path path;
        try {
            path = attachmentReferences.resolve(id, file);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        String lower = file.toLowerCase(Locale.ROOT);
        MediaType type = lower.endsWith(".png") ? MediaType.IMAGE_PNG
            : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? MediaType.IMAGE_JPEG : lower.endsWith(".gif")
                ? MediaType.IMAGE_GIF : lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : null;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, max-age=86400");
        if (type != null) {
            return response.contentType(type).body(new FileSystemResource(path));
        }
        String downloadName = safeDownloadName(originalName, path.getFileName().toString());
        return response.contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(path));
    }

    /** Office 附件的纯文本读回；不还原版式，也不执行附件中的 HTML/脚本。 */
    @GetMapping("/{id}/attachments/{file}/preview-text")
    public ResponseEntity<?> attachmentPreviewText(@PathVariable String id, @PathVariable String file) {
        requireSession(id);
        Path path;
        try {
            path = attachmentReferences.resolve(id, file);
            if (!DocumentRenderer.isRenderable(path.getFileName().toString())) {
                throw new IllegalArgumentException("不支持此附件的内容预览");
            }
            String text = DocumentRenderer.inspect(path);
            if (text.length() > 250_000) {
                text = text.substring(0, 250_000) + "\n…内容过长，已截断";
            }
            return ResponseEntity.ok(Map.of("text", text));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "附件内容无法读取", e);
        }
    }

    private static String safeDownloadName(String originalName, String storedName) {
        String name = originalName == null || originalName.isBlank() ? storedName.replaceFirst("^[a-fA-F0-9-]{36}-", "")
            : originalName;
        String safe = name.replaceAll("[\\\\/\\r\\n\\p{Cntrl}]", "_").strip();
        return safe.isBlank() ? "attachment" : safe;
    }

    /** 改队列里的某一句，下标从 0 开始。还没轮到的才能改。 */
    @PutMapping("/{id}/queue/{index}")
    public QueueResponse editQueued(@PathVariable String id, @PathVariable int index,
        @RequestBody(required = false) QueueEditRequest request) {
        requireSession(id);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        if (!runStarter.editQueued(id, index, request.content(), request.revision())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "队列已变化或消息不存在，请刷新后重试");
        }
        return queueResponse(id);
    }

    /** 往前排一位。整条消息（含图片）一起移动，所以不能靠前端互换两条的文字。 */
    @PostMapping("/{id}/queue/{index}/up")
    public QueueResponse moveQueuedUp(@PathVariable String id, @PathVariable int index) {
        requireSession(id);
        if (!runStarter.moveQueuedUp(id, index)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这一句已经在最前面，或已经不在队列里");
        }
        return queueResponse(id);
    }

    /** 拖拽排序：把 index 这一句挪到 to 的位置，整条消息（含图片）一起移动。 */
    @PostMapping("/{id}/queue/{index}/move")
    public QueueResponse moveQueued(@PathVariable String id, @PathVariable int index, @RequestParam int to,
        @RequestParam(required = false) String revision) {
        requireSession(id);
        if (!runStarter.moveQueued(id, index, to, revision)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "位置无效，或这一句已经不在队列里");
        }
        return queueResponse(id);
    }

    @DeleteMapping("/{id}/queue/{index}")
    public QueueResponse removeQueued(@PathVariable String id, @PathVariable int index,
        @RequestParam(required = false) String revision) {
        requireSession(id);
        if (!runStarter.removeQueued(id, index, revision)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "队列已变化或消息不存在，请刷新后重试");
        }
        return queueResponse(id);
    }

    @PostMapping("/{id}/queue/{index}/steer")
    public QueueResponse steerQueued(@PathVariable String id, @PathVariable int index,
        @RequestBody QueueSteerRequest request) {
        requireSession(id);
        if (request == null || request.runId() == null || request.revision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少运行或队列版本，请刷新后重试");
        }
        if (!runStarter.steerQueued(id, request.runId(), index, request.revision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务或队列已变化，引导未发送，请查看最新队列");
        }
        return queueResponse(id);
    }

    public record QueueSteerRequest(String runId, String revision) {
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
            if (events.get(i).payload() instanceof RunStarted started && !"（继续上一次未完成的任务）".equals(started.input())) {
                return started.input();
            }
        }
        return null;
    }

    @PostMapping("/{id}/steer")
    public ResponseEntity<MessageResponse> steer(@PathVariable String id, @RequestBody SteerRequest request) {
        requireSession(id);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "引导内容不能为空");
        }
        String runId = request.runId() == null ? runStarter.activeRun(id).orElse(null) : request.runId();
        if (runId == null || !runStarter.steer(id, runId, request.content())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务已结束或正在停止，引导未发送");
        }
        return ResponseEntity.accepted().body(new MessageResponse(id, runId));
    }

    public record SteerRequest(String content, String runId) {
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable String id, @RequestBody MessageRequest request) {
        requireSession(id);
        boolean hasAttachments = request != null && request.attachments() != null && !request.attachments().isEmpty();
        if (request == null || ((request.content() == null || request.content().isBlank()) && !hasAttachments)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        String skill = request.skill() == null || request.skill().isBlank() ? null : request.skill().strip();
        if (skill != null) {
            // 激活是启动期行为，排队消息无法携带；错误信息直接给出可行动的原因
            var catalog = skills.catalogFor(workspaceOf(id));
            if (catalog.find(skill).isEmpty()) {
                String reason = catalog.invalid().stream().filter(problem -> problem.name().equals(skill))
                    .map(problem -> problem.reason()).findFirst().orElse("未找到");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "技能 " + skill + " 不可用：" + reason);
            }
        }
        // 运行中的消息进队列，附件随消息保存；独立验收条件只属于一次新任务的启动，技能是启动期行为，都不能排队。
        // 先检查状态，避免被拒绝的请求产生文件副作用。
        String activeRun = runStarter.activeRun(id).orElse(null);
        if (activeRun != null && (request.task() != null || skill != null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "排队消息暂不支持独立验收条件和技能，请等本轮结束后再发");
        }
        AttachmentService.Prepared prepared;
        try {
            prepared = attachments.prepare(id, request.content(), request.attachments(), request.imagePositions());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (activeRun != null) {
            if (runStarter.followup(id, prepared.content(), prepared.attachments(), prepared.imagePositions())) {
                sessions.touch(id);
                return ResponseEntity.accepted().body(new MessageResponse(id, activeRun));
            }
        }
        // 检查与启动之间可能刚好起了一个 run：抢占失败就退化成追加输入
        String runId = runStarter.claimRun(id).orElse(null);
        if (runId == null) {
            if (request.task() != null || skill != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "运行状态已变化，请结束当前任务后重试");
            }
            if (runStarter.followup(id, prepared.content(), prepared.attachments(), prepared.imagePositions())) {
                sessions.touch(id);
                return ResponseEntity.accepted().body(new MessageResponse(id, runStarter.activeRun(id).orElse(null)));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话仍有运行在进行");
        }
        RunSubmissionService.start(sessions, runStarter, id, runId, prepared.content(), prepared.attachments(),
            prepared.imagePositions(), request.task(), skill);
        return ResponseEntity.ok().body(new MessageResponse(id, runId));
    }

    private String workspaceOf(String sessionId) {
        return sessions.find(sessionId).map(Session::workspace).orElse(null);
    }

    /**
     * SSE 事件流（服务端 → 客户端单向推送）。
     * 支持 {@code Last-Event-ID} 请求头或 {@code after} 查询参数作为游标，用于断线重连。
     */
    /**
     * 历史事件分页：界面首屏只取尾部一页，避免长会话一次性回放上千条事件。
     *
     * @param before 只返回序号小于它的历史，便于向上翻页
     * @param after  只返回序号大于它的历史，便于窗口回收后向下翻页
     */
    @GetMapping("/{id}/history")
    public HistoryResponse history(@PathVariable String id,
        @RequestParam(name = "limit", defaultValue = "60") int limit,
        @RequestParam(name = "before", required = false) Long before,
        @RequestParam(name = "after", required = false) Long after) {
        requireSession(id);
        if (before != null && after != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before 和 after 不能同时使用");
        }
        int capped = Math.max(1, Math.min(limit, 500));
        List<EventEnvelope> page =
            after != null ? sessions.eventsPageAfter(id, after, capped) : sessions.eventsPage(id, before, capped);
        boolean hasMore =
            after != null ? !page.isEmpty() && page.get(page.size() - 1).sequence() < sessions.latestSequence(id)
                : !page.isEmpty() && page.get(0).sequence() > 1;
        return new HistoryResponse(page, hasMore, countOf(id));
    }

    /**
     * @param hasMore 是否还有更早的事件
     * @param total   该会话的事件总数
     */
    public record HistoryResponse(List<EventEnvelope> events, boolean hasMore, int total) {
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id,
        @RequestParam(name = "after", required = false) Long after,
        @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        requireSession(id);
        long cursor = after != null ? after : lastEventId != null ? lastEventId : 0L;
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<EventSubscription> subscription = new AtomicReference<EventSubscription>();
        AtomicReference<Thread> heartbeat = new AtomicReference<>();

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

        emitter.onCompletion(() -> closeQuietly(closed, subscription, heartbeat, null));
        emitter.onTimeout(() -> closeQuietly(closed, subscription, heartbeat, null));
        emitter.onError(error -> closeQuietly(closed, subscription, heartbeat, null));
        try {
            emitter.send(SseEmitter.event().comment("stream open: session=" + id + ", after=" + cursor));
            // 第四个参数：订阅被服务端摘掉（客户端积压太多）时，把底层连接一起关掉
            subscription.set(
                sessions.subscribe(id, cursor, listener, () -> closeQuietly(closed, subscription, heartbeat, emitter)));
            if (closed.get()) {
                subscription.get().close();
            }
            // 空闲连接也定期写注释帧：对端早已断开时，下一次写失败就能把订阅清掉
            Thread beat = Thread.ofVirtual().name("pilot-sse-heartbeat").unstarted(() -> {
                try {
                    while (!closed.get()) {
                        Thread.sleep(java.time.Duration.ofSeconds(HEARTBEAT_SECONDS));
                        if (!closed.get()) emitter.send(SseEmitter.event().comment("ping"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    closeQuietly(closed, subscription, heartbeat, emitter);
                }
            });
            heartbeat.set(beat);
            if (!closed.get()) beat.start();
            if (closed.get()) beat.interrupt();
        } catch (IOException | RuntimeException e) {
            closeQuietly(closed, subscription, heartbeat, null);
            emitter.completeWithError(e);
        }
        return ResponseEntity.ok().header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no").body(emitter);
    }

    /** @return {@code false} 表示客户端已经断开，调用方应停止推送并注销订阅 */
    private boolean send(SseEmitter emitter, EventEnvelope envelope) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(envelope.sequence())).name(envelope.type())
                .data(objectMapper.writeValueAsString(envelope)));
            return true;
        } catch (IOException | IllegalStateException e) {
            // ClientAbortException / AsyncRequestNotUsableException：对端已关闭，静默收尾
            return false;
        }
    }

    private static void closeQuietly(AtomicBoolean closed, AtomicReference<EventSubscription> subscription,
        AtomicReference<Thread> heartbeat, SseEmitter emitter) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Thread beat = heartbeat.get();
        if (beat != null) {
            beat.interrupt();
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
        return sessions.find(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
    }

    public record CreateSessionRequest(String title, String workspace, String projectId, Boolean replaceBlank) {
    }

    public record RenameRequest(String title) {
    }

    public record MessageRequest(String content, List<AttachmentService.Inbound> attachments,
        TaskControlService.Requirements task, String skill, List<Integer> imagePositions) {
        public MessageRequest(String content, List<AttachmentService.Inbound> attachments,
            TaskControlService.Requirements task) {
            this(content, attachments, task, null, List.of());
        }
    }

    public record ResendRequest(Long sequence, String content) {
    }

    public record QueueEditRequest(String content, String revision) {
    }

    /**
     * @param items   排队消息正文，顺序即执行顺序（保持旧契约）
     * @param entries 同一批消息的结构化版本，额外带上图片附件和它们在正文里的位置
     */
    public record QueueResponse(List<String> items, List<QueueEntry> entries, String revision) {
    }

    public record QueueEntry(String content, List<AttachmentService.DisplayAttachment> attachments,
        List<Integer> imagePositions) {
    }

    public record MessageResponse(String sessionId, String runId) {
    }

    public record ActiveRunResponse(String sessionId, String runId) {
    }

    public record SessionResponse(String id, String sessionId, String title, Instant createdAt, SessionStatus status,
        String workspace, String projectId, boolean isBlank, boolean archived, int eventCount, String parentSessionId,
        Instant updatedAt, boolean pinned, boolean unread, boolean titleManual) {
        static SessionResponse of(Session session, int eventCount) {
            // 同时保留 id 与 sessionId：前端界面（含 React 版）统一按 id 读取会话标识，
            // 旧版 app.js 仍按 sessionId 读取，二者并存避免任一侧取到 undefined。
            return new SessionResponse(session.id(), session.id(), session.title(), session.createdAt(),
                session.status(), session.workspace(), session.projectId(), session.isBlank(), session.archived(),
                eventCount, session.parentSessionId(), session.updatedAt(), session.pinned(), session.unread(),
                session.titleManual());
        }
    }
}
