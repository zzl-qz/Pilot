package com.pilot.server.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.ModelRequestCompleted;
import com.pilot.core.event.PlanUpdated;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.ContextCompactor;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ContextComposition;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.RequestReport;
import com.pilot.core.model.RequestTotals;
import com.pilot.core.multi.AgentProfileCatalog;
import com.pilot.core.multi.AgentScope;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.tool.CommandLogsTool;
import com.pilot.core.tool.CommandStatusTool;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.core.tool.RunCommandTool;
import com.pilot.core.tool.StopCommandTool;
import com.pilot.core.tool.ToolNames;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UseSkillTool;
import com.pilot.core.trace.JsonlTraceRecorder;
import com.pilot.core.util.Texts;
import com.pilot.core.util.WireEnum;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.model.ModelSettings;
import com.pilot.server.multi.DispatchAgentsTool;
import com.pilot.server.multi.MultiAgentService;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.workspace.RunSnapshotService;
import com.pilot.server.workspace.WorkspaceToolFactory;

import tools.jackson.databind.ObjectMapper;

/**
 * 在独立线程上执行 {@link AgentLoop}，把循环产生的事件写入会话。
 *
 * <p>对话记忆通过 {@code ConversationRepository} 落盘（默认 SQLite），每步结束都会 checkpoint，
 * 因此进程重启后可以用 {@code /continue} 接着跑；计划通过 {@code PlanRepository} 保存，
 * 并在每次运行时重新注入 system，保证压缩之后计划仍然可见。
 */
@Component
@org.springframework.context.annotation.DependsOn("historyEditCoordinator")
public class AgentRunService implements RunStarter {
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final int MAX_AUTO_CONTINUE = 3;
    /** 这些失败码与任务状态同名：运行停下后任务直接记为同名状态，不归并成 INTERRUPTED。 */
    private static final Set<TaskControlStatus> PRESERVED_FAILURE_STATUSES =
        Set.of(TaskControlStatus.BLOCKED, TaskControlStatus.BUDGET_LIMITED, TaskControlStatus.WAITING,
            TaskControlStatus.NEED_INPUT, TaskControlStatus.UNVERIFIED, TaskControlStatus.NO_PROGRESS,
            TaskControlStatus.REVIEW_UNAVAILABLE, TaskControlStatus.VERIFICATION_FAILED);
    /** 提示词正文在 {@code prompts/} 资源里，这里只做变量注入与拼接。 */
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();
    /** 仓库快照：顶层条目与未提交改动都限行，避免 system 被大仓库撑爆。 */
    private static final int MAX_TREE_ENTRIES = 30;
    private static final int MAX_GIT_CHANGES = 12;
    private static final long GIT_TIMEOUT_MS = 2_000L;
    private static final Set<String> IGNORED_ENTRIES = Set.of(".git", ".pilot", ".idea", ".vscode", ".gradle", ".venv",
        "__pycache__", "node_modules", "target", "build", "dist", "out");

    private final SessionService sessions;
    private final ModelClient modelClient;
    private final WorkspaceToolFactory toolFactory;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentProperties properties;
    private final ModelSettings modelSettings;
    private final RunSnapshotService snapshots;
    private final RunRegistry registry;
    private final TaskControlService taskControls;
    private final CommandTaskManager commandTasks;
    private final com.pilot.core.session.RunRepository runRepository;
    private final ExecutorService executor;
    private final ConversationRepository conversations;
    private final com.pilot.core.session.PlanRepository plans;
    private final ConcurrentMap<String, RunHandle> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<String>> inboxes = new ConcurrentHashMap<>();
    private final com.pilot.server.store.QueuedMessageRepository queuedMessages;
    private final ConcurrentMap<String, Integer> autoContinueAttempts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> autoContinueSignature = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final PilotDataDir dataDir;
    private final MultiAgentService multiAgent;
    private final MultiAgentProperties multiProperties;
    private final com.pilot.server.observability.RunMetrics metrics;
    private final SkillService skills;
    /** 可恢复调度状态；为空时（测试构造）不做检查点，也不影响执行。 */
    private final RunStateTracker runStates;
    private final com.pilot.server.store.ToolCallJournalFactory journals;
    private final com.pilot.server.validation.ValidationLedger validations;
    /** 自定义子代理目录；测试轻量装配不注入时，spawn/dispatch 的 agent 引用只能拿到空目录。 */
    private volatile AgentProfileService agentProfiles;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.pilot.server.schedule.ScheduleService> scheduleService;
    private volatile com.pilot.server.desktop.DesktopViewportReset viewportReset;
    private com.pilot.server.work.WorkProductService workProducts;
    private com.pilot.server.work.SkillDraftService skillDrafts;
    private SkillInstallService skillInstaller;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSkillInstaller(SkillInstallService skillInstaller) {
        this.skillInstaller = skillInstaller;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setWorkProducts(com.pilot.server.work.WorkProductService workProducts,
        com.pilot.server.work.SkillDraftService skillDrafts) {
        this.workProducts = workProducts;
        this.skillDrafts = skillDrafts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAgentProfiles(AgentProfileService agentProfiles) {
        this.agentProfiles = agentProfiles;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setViewportReset(com.pilot.server.desktop.DesktopViewportReset viewportReset) {
        this.viewportReset = viewportReset;
    }

    /** 兼容旧调用：不启用检查点与幂等账本。 */
    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
        PermissionGate permissionGate, ApprovalBroker approvals, AgentProperties properties,
        ModelSettings modelSettings, RunSnapshotService snapshots, RunRegistry registry,
        com.pilot.core.session.RunRepository runRepository, ConversationRepository conversations,
        com.pilot.core.session.PlanRepository plans, ObjectMapper objectMapper, PilotDataDir dataDir,
        com.pilot.server.store.QueuedMessageRepository queuedMessages, MultiAgentService multiAgent,
        MultiAgentProperties multiProperties, com.pilot.server.observability.RunMetrics metrics,
        TaskControlService taskControls, CommandTaskManager commandTasks) {
        this(sessions, modelClient, toolFactory, permissionGate, approvals, properties, modelSettings, snapshots,
            registry, runRepository, conversations, plans, objectMapper, dataDir, queuedMessages, multiAgent,
            multiProperties, metrics, taskControls, commandTasks, null, null, null, null);
    }

    /** 技能链路构造：技能可用，检查点与幂等账本关闭；测试与轻量装配用。 */
    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
        PermissionGate permissionGate, ApprovalBroker approvals, AgentProperties properties,
        ModelSettings modelSettings, RunSnapshotService snapshots, RunRegistry registry,
        com.pilot.core.session.RunRepository runRepository, ConversationRepository conversations,
        com.pilot.core.session.PlanRepository plans, ObjectMapper objectMapper, PilotDataDir dataDir,
        com.pilot.server.store.QueuedMessageRepository queuedMessages, MultiAgentService multiAgent,
        MultiAgentProperties multiProperties, com.pilot.server.observability.RunMetrics metrics,
        TaskControlService taskControls, CommandTaskManager commandTasks, SkillService skills) {
        this(sessions, modelClient, toolFactory, permissionGate, approvals, properties, modelSettings, snapshots,
            registry, runRepository, conversations, plans, objectMapper, dataDir, queuedMessages, multiAgent,
            multiProperties, metrics, taskControls, commandTasks, skills, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
        PermissionGate permissionGate, ApprovalBroker approvals, AgentProperties properties,
        ModelSettings modelSettings, RunSnapshotService snapshots, RunRegistry registry,
        com.pilot.core.session.RunRepository runRepository, ConversationRepository conversations,
        com.pilot.core.session.PlanRepository plans, ObjectMapper objectMapper, PilotDataDir dataDir,
        com.pilot.server.store.QueuedMessageRepository queuedMessages, MultiAgentService multiAgent,
        MultiAgentProperties multiProperties, com.pilot.server.observability.RunMetrics metrics,
        TaskControlService taskControls, CommandTaskManager commandTasks, SkillService skills,
        RunStateTracker runStates, com.pilot.server.store.ToolCallJournalFactory journals,
        com.pilot.server.validation.ValidationLedger validations) {
        this.sessions = sessions;
        this.runStates = runStates;
        this.journals = journals;
        this.validations = validations;
        this.taskControls = taskControls;
        this.commandTasks = commandTasks;
        this.conversations = conversations;
        this.plans = plans;
        this.modelClient = modelClient;
        this.toolFactory = toolFactory;
        this.permissionGate = permissionGate;
        this.approvals = approvals;
        this.properties = properties;
        this.modelSettings = modelSettings;
        this.snapshots = snapshots;
        this.registry = registry;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.dataDir = dataDir;
        this.queuedMessages = queuedMessages;
        this.multiAgent = multiAgent;
        this.multiProperties = multiProperties;
        this.metrics = metrics;
        this.skills = skills;
        this.executor = Executors.newFixedThreadPool(properties.runThreads(), runnable -> {
            Thread thread = new Thread(runnable, "pilot-agent-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 把工作区 {@code .pilot/settings.json} 里的 allow/deny/ask 规则叠在原 gate 前面。
     * 没有任何规则时原样返回，行为与之前完全一致。
     */
    private static PermissionGate withWorkspaceRules(PermissionGate base, java.nio.file.Path workspace) {
        com.pilot.core.permission.PermissionRules rules = com.pilot.core.permission.PermissionRules.load(workspace);
        if (rules.isEmpty()) {
            return base;
        }
        // 规则对只读工具同样生效（例如禁止读 .env），未命中才回落到档位
        return (call, tool) -> {
            com.pilot.core.permission.PermissionResult baseline = base.check(call, tool);
            if (baseline.decision() == com.pilot.core.permission.PermissionDecision.DENY) {
                return baseline;
            }
            com.pilot.core.permission.PermissionResult ruled = rules.evaluate(call);
            return ruled != null ? ruled : baseline;
        };
    }

    @Override
    public void start(String sessionId, String runId, String input) {
        launch(sessionId, runId, input, List.of(), false);
    }

    @Override
    public void start(String sessionId, String runId, String input, List<Attachment> attachments) {
        launch(sessionId, runId, input, attachments == null ? List.of() : attachments, false);
    }

    @Override
    public void start(String sessionId, String runId, String input, List<Attachment> attachments,
        TaskControlService.Requirements task) {
        launch(sessionId, runId, input, attachments, List.of(), false, task);
    }

    @Override
    public void start(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, TaskControlService.Requirements task) {
        launch(sessionId, runId, input, attachments, imagePositions, false, task);
    }

    /** 指定技能启动：技能存在性由控制器校验，指引注入由 run 执行链完成。 */
    @Override
    public void start(String sessionId, String runId, String input, List<Attachment> attachments,
        TaskControlService.Requirements task, String skill) {
        launch(sessionId, runId, input, attachments == null ? List.of() : attachments, List.of(), false, task, false,
            skill == null || skill.isBlank() ? null : skill.strip());
    }

    /** 图片位置与技能同时支持的完整启动：控制器的合并链路走这里。 */
    @Override
    public void start(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, TaskControlService.Requirements task, String skill) {
        launch(sessionId, runId, input, attachments == null ? List.of() : attachments, imagePositions, false, task,
            false, skill == null || skill.isBlank() ? null : skill.strip());
    }

    @Override
    public boolean steer(String sessionId, String text) {
        return steer(sessionId, registry.activeRun(sessionId).orElse(null), text);
    }

    @Override
    public boolean steer(String sessionId, String expectedRunId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        synchronized (inbox(sessionId)) {
            String runId = registry.activeRun(sessionId).orElse(null);
            if (runId == null || !runId.equals(expectedRunId)) {
                return false;
            }
            RunHandle handle = runId == null ? null : runs.get(runId);
            if (handle == null || !handle.accepting || handle.cancelled.get()) {
                return false;
            }
            taskControls.correct(sessionId, text.strip());
            ArrayList<String> pending = new ArrayList<>(queuedMessages.load("steer:" + sessionId));
            pending.add(text.strip());
            queuedMessages.save("steer:" + sessionId, pending);
            handle.pendingApprovals.forEach(callId -> approvals.resolve(callId, false));
            return true;
        }
    }

    @Override
    public void discardSteering(String sessionId) {
        synchronized (inbox(sessionId)) {
            queuedMessages.save("steer:" + sessionId, List.of());
        }
    }

    @Override
    public boolean steerQueued(String sessionId, String expectedRunId, int index, String revision) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            String runId = registry.activeRun(sessionId).orElse(null);
            RunHandle handle = runId == null ? null : runs.get(runId);
            if (runId == null || !runId.equals(expectedRunId) || handle == null || !handle.accepting
                || handle.cancelled.get() || index < 0 || index >= queue.size()
                || !RunStarter.queueRevision(queuedMessages(sessionId)).equals(revision)) {
                return false;
            }
            ArrayList<String> remaining = new ArrayList<>(queue);
            String raw = remaining.remove(index);
            var item = QueuedMessageCodec.decode(raw);
            ArrayList<String> pending = new ArrayList<>(queuedMessages.load("steer:" + sessionId));
            pending.add(raw);
            queuedMessages.saveAll(Map.of(sessionId, remaining, "steer:" + sessionId, pending));
            queue.clear();
            queue.addAll(remaining);
            taskControls.correct(sessionId, item.content().isBlank() ? "请参考补充的图片" : item.content());
            handle.pendingApprovals.forEach(callId -> approvals.resolve(callId, false));
            return true;
        }
    }

    private Message pollSteering(String sessionId) {
        synchronized (inbox(sessionId)) {
            List<String> pending = queuedMessages.load("steer:" + sessionId);
            if (pending.isEmpty()) {
                return null;
            }
            var item = QueuedMessageCodec.decode(pending.getFirst());
            queuedMessages.save("steer:" + sessionId, pending.subList(1, pending.size()));
            return Message.user(item.content(), item.attachments(), item.imagePositions());
        }
    }

    private boolean claimCompletion(String sessionId, RunHandle handle) {
        synchronized (inbox(sessionId)) {
            if (handle.cancelled.get() || !queuedMessages.load("steer:" + sessionId).isEmpty()) {
                return false;
            }
            handle.accepting = false;
            return true;
        }
    }

    @Override
    public boolean pause(String runId) {
        RunHandle handle = runs.get(runId);
        if (handle == null) {
            return false;
        }
        synchronized (inbox(handle.sessionId)) {
            if (!handle.accepting) {
                return false;
            }
            handle.paused = true;
            handle.accepting = false;
            handle.cancelled.set(true);
            handle.pendingApprovals.forEach(callId -> approvals.resolve(callId, false));
            return true;
        }
    }

    /** 续跑：不追加用户消息，用落盘的记忆接着跑。 */
    @Override
    public void resume(String sessionId, String runId) {
        launch(sessionId, runId, null, List.of(), true);
    }

    @Override
    public boolean resumable(String sessionId) {
        return registry.activeRun(sessionId).isEmpty() && !conversations.load(sessionId).isEmpty();
    }

    @Override
    public ContextStatus contextStatus(String sessionId) {
        List<Message> history = conversations.load(sessionId);
        int used = ContextCompactor.estimateTokens(history);
        int limit = properties.contextMaxChars();
        return new ContextStatus(history.size(), used, limit, true,
            registry.activeRun(sessionId).isEmpty() && history.size() > 3 && used >= 1_000,
            Math.min(100, Math.round(used * 100f / limit)), segments(history));
    }

    /**
     * 模型记忆按角色的构成：看得出「什么在占地方」——工具结果通常是大头，
     * 压缩折叠的也正是它。角色顺序固定（user / assistant / tool / system），
     * 前端按序叠加成占比条。
     */
    private static List<ContextStatus.Segment> segments(List<Message> history) {
        Map<String, int[]> byRole = new java.util.LinkedHashMap<>();
        for (Message message : history) {
            int[] totals = byRole.computeIfAbsent(message.role(), ignored -> new int[3]);
            totals[0] += 1;
            totals[1] += message.content() == null ? 0 : message.content().length();
        }
        List<ContextStatus.Segment> segments = new ArrayList<>();
        for (String role : List.of("user", "assistant", "tool", "system")) {
            int[] totals = byRole.remove(role);
            if (totals != null) {
                segments.add(new ContextStatus.Segment(role, totals[0], totals[1]));
            }
        }
        // 罕见的其他角色照实保留，不吞数据
        byRole.forEach((role, totals) -> segments.add(new ContextStatus.Segment(role, totals[0], totals[1])));
        return segments;
    }

    @Override
    public CompactionResult compactContext(String sessionId) {
        synchronized (inbox(sessionId)) {
            if (registry.activeRun(sessionId).isPresent()) {
                throw new IllegalStateException("任务运行中不能压缩，请先暂停或等待本轮结束");
            }
            List<Message> history = conversations.load(sessionId);
            int tokensBefore = ContextCompactor.estimateTokens(history);
            if (history.size() <= 3 || tokensBefore < 1_000) {
                return new CompactionResult(false, 0, tokensBefore, tokensBefore, "当前上下文还很轻，不需要压缩");
            }

            ArrayList<Message> working = new ArrayList<Message>(history.size() + 1);
            // ContextCompactor 把 index 0 当 system 保留；真实 system 会在下一次请求时重建。
            working.add(Message.system("Pilot context boundary"));
            working.addAll(history);
            int charsBefore = ContextCompactor.totalChars(working);
            int targetChars =
                Math.max(1_000, Math.min(properties.contextMaxChars() / 2, Math.max(1_000, charsBefore * 2 / 3)));
            int targetTokens =
                Math.max(1_000, Math.min(properties.contextMaxChars() / 2, Math.max(1_000, tokensBefore * 2 / 3)));
            String compactRun = "compact_" + java.util.UUID.randomUUID();
            RunUsageAccumulator manualUsage = new RunUsageAccumulator();
            ModelClient measured = new MeteredModelClient(modelClient, sessionId, "compaction", List.of(), report -> {
                publish(sessionId, compactRun, new ModelRequestCompleted(report));
                publish(sessionId, compactRun, manualUsage.request(report));
            });
            ContextCompactor.Outcome outcome =
                new ContextCompactor(measured).compact(working, targetChars, targetTokens);
            if (!outcome.compacted()) {
                return new CompactionResult(false, 0, tokensBefore, tokensBefore, "没有足够的早期内容可安全折叠，模型记忆保持不变");
            }

            List<Message> compacted = List.copyOf(working.subList(1, working.size()));
            conversations.save(sessionId, compacted);
            int tokensAfter = ContextCompactor.estimateTokens(compacted);
            publish(sessionId, compactRun, new ContextCompacted(outcome.coveredMessages(), outcome.charactersBefore(),
                outcome.charactersAfter(), outcome.summary()));
            return new CompactionResult(true, outcome.coveredMessages(), tokensBefore, tokensAfter,
                "已压缩 " + outcome.coveredMessages() + " 条较早消息");
        }
    }

    @Override
    public boolean followup(String sessionId, String text) {
        return followup(sessionId, text, List.of(), List.of());
    }

    @Override
    public boolean followup(String sessionId, String text, List<Attachment> attachments, List<Integer> imagePositions) {
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if ((text == null || text.isBlank()) && !hasAttachments) {
            return false;
        }
        String content = text == null ? "" : text.strip();
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            // 与运行结束后的队列交接共用锁，避免检查完活动运行后才入队，消息无人接走。
            if (registry.activeRun(sessionId).isEmpty()) {
                return false;
            }
            queue.offer(QueuedMessageCodec.encode(new QueuedMessageCodec.Stored(content, attachments, imagePositions)));
            persistQueue(sessionId, queue);
        }
        return true;
    }

    @Override
    public List<String> queued(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            return queue.stream().map(raw -> QueuedMessageCodec.decode(raw).content()).toList();
        }
    }

    @Override
    public List<QueuedMessage> queuedMessages(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            return queueEntries(queue);
        }
    }

    private static List<QueuedMessage> queueEntries(Deque<String> queue) {
        return queue.stream().map(QueuedMessageCodec::decode)
            .map(item -> new QueuedMessage(item.content(), item.attachments(), item.imagePositions())).toList();
    }

    @Override
    public boolean editQueued(String sessionId, int index, String text, String revision) {
        return updateQueue(sessionId, revision, items -> {
            if (text == null || text.isBlank() || index < 0 || index >= items.size()) {
                return false;
            }
            // 只改文字，图片附件跟着这条消息走。
            items.set(index,
                QueuedMessageCodec.encode(QueuedMessageCodec.decode(items.get(index)).withContent(text.strip())));
            return true;
        });
    }

    @Override
    public boolean moveQueued(String sessionId, int from, int to, String revision) {
        return updateQueue(sessionId, revision, items -> {
            if (from < 0 || from >= items.size() || to < 0 || to >= items.size() || from == to) {
                return false;
            }
            items.add(to, items.remove(from));
            return true;
        });
    }

    @Override
    public boolean removeQueued(String sessionId, int index, String revision) {
        return updateQueue(sessionId, revision, items -> {
            if (index < 0 || index >= items.size()) {
                return false;
            }
            items.remove(index);
            return true;
        });
    }

    @Override
    public boolean editQueued(String sessionId, int index, String text) {
        return editQueued(sessionId, index, text, null);
    }

    @Override
    public boolean moveQueuedUp(String sessionId, int index) {
        return moveQueued(sessionId, index, index - 1, null);
    }

    @Override
    public boolean moveQueued(String sessionId, int from, int to) {
        return moveQueued(sessionId, from, to, null);
    }

    @Override
    public boolean removeQueued(String sessionId, int index) {
        return removeQueued(sessionId, index, null);
    }

    /** 版本核对和持久化在同一把队列锁内完成。 */
    private boolean updateQueue(String sessionId, String revision, Predicate<ArrayList<String>> edit) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            if (revision != null && !RunStarter.queueRevision(queueEntries(queue)).equals(revision)) {
                return false;
            }
            ArrayList<String> items = new ArrayList<>(queue);
            if (!edit.test(items)) {
                return false;
            }
            queue.clear();
            queue.addAll(items);
            persistQueue(sessionId, queue);
            return true;
        }
    }

    @Override
    public void rewindBeforeUserInput(String sessionId, int occurrence, String originalInput) {
        if (occurrence < 1 || originalInput == null) {
            return;
        }
        List<Message> history = conversations.load(sessionId);
        int seen = 0;
        int cut = -1;
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            if (!"user".equals(message.role())) {
                continue;
            }
            String text = message.content() == null ? "" : message.content();
            // 自动续跑塞进去的提示不是用户自己写的，不能拿来对位置
            if (text.startsWith("[自动继续]") || !text.equals(originalInput)) {
                continue;
            }
            seen++;
            if (seen == occurrence) {
                cut = i;
                break;
            }
        }
        if (cut >= 0) {
            conversations.save(sessionId, history.subList(0, cut));
        }
        plans.delete(sessionId);
    }

    private Deque<String> inbox(String sessionId) {
        return inboxes.computeIfAbsent(sessionId, key -> new ArrayDeque<>(queuedMessages.load(key)));
    }

    private void persistQueue(String sessionId, Deque<String> queue) {
        queuedMessages.save(sessionId, List.copyOf(queue));
    }

    private void launch(String sessionId, String runId, String input, List<Attachment> attachments,
        boolean continuation) {
        launch(sessionId, runId, input, attachments, List.of(), continuation, null);
    }

    private void launch(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, boolean continuation, TaskControlService.Requirements requirements) {
        launch(sessionId, runId, input, attachments, imagePositions, continuation, requirements, false, null);
    }

    private void launch(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, boolean continuation, TaskControlService.Requirements requirements,
        boolean regenerate, String skill) {
        synchronized (inbox(sessionId)) {
            RunHandle handle = new RunHandle(sessionId, skill);
            runs.put(runId, handle);
            try {
                registry.register(runId, sessionId, sessionWorkspace(sessionId));
                if (runStates != null) {
                    runStates.begin(sessionId, runId, sessionWorkspace(sessionId).toString());
                }
                snapshots.saveConversation(runId, conversations.load(sessionId), plans.load(sessionId));
                taskControls.snapshot(sessionId, runId);
                TaskControlService.TaskContract previous = taskControls.load(sessionId);
                if (!continuation && !regenerate) {
                    if (previous != null) {
                        plans.delete(sessionId);
                    }
                    taskControls.start(sessionId, input, requirements == null ? List.of() : requirements.constraints(),
                        requirements == null ? List.of() : requirements.acceptance());
                    if (requirements != null && requirements.automation() != null) {
                        taskControls.configureAutomation(sessionId, requirements.automation());
                    }
                    queuedMessages.save("steer:" + sessionId, List.of());
                } else if (previous != null) {
                    if (continuation && previous.automation() == null
                        && TaskControlStatus.BUDGET_LIMITED.is(previous.status())) {
                        taskControls.extendBudget(sessionId);
                    }
                    if (regenerate) {
                        taskControls.reassess(sessionId);
                        plans.delete(sessionId);
                        taskControls.update(sessionId, previous.constraints(),
                            previous.acceptance().stream()
                                .map(item -> new TaskControlService.Acceptance(item.text(), false, item.evidence()))
                                .toList());
                        queuedMessages.save("steer:" + sessionId, List.of());
                    }
                    taskControls.status(sessionId, TaskControlStatus.ACTIVE);
                } else {
                    String original = sessions.events(sessionId).stream()
                        .filter(event -> event.payload() instanceof RunStarted started
                            && !started.input().equals("（继续上一次未完成的任务）"))
                        .map(event -> ((RunStarted)event.payload()).input()).reduce((first, last) -> last)
                        .orElse("继续对话中尚未完成的任务");
                    taskControls.start(sessionId, original, List.of(), List.of());
                }
                runRepository.started(runId, sessionId, java.time.Instant.now());
                handle.future = executor.submit(() -> {
                    try (var activity = dataDir.maintenance().activity()) {
                        runOnWorker(sessionId, runId, input, attachments, imagePositions, handle, continuation);
                    }
                });
            } catch (RuntimeException e) {
                runs.remove(runId, handle);
                registry.unregister(runId);
                throw e;
            }
        }
    }

    /** 工作线程主体：无论怎么结束都要注销槽位，否则 /continue 会永久 409。 */
    private void runOnWorker(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, RunHandle handle, boolean continuation) {
        synchronized (handle) {
            handle.workerThread = Thread.currentThread();
        }
        long startedAt = System.currentTimeMillis();
        String status = TaskControlStatus.FAILED.name();
        boolean continued = false;
        boolean resultCaptured = false;
        try {
            status = execute(sessionId, runId, input, attachments, imagePositions, handle, continuation);
            captureResult(sessionId, runId);
            resultCaptured = true;
            continued = maybeAutoContinue(sessionId, runId, status, handle);
        } catch (RuntimeException e) {
            log.error("Run {} 收尾失败", runId, e);
            taskControls.status(sessionId, TaskControlStatus.FAILED);
            publish(sessionId, runId, new RunFailed(RunFailureCode.INTERNAL_ERROR, "执行失败：" + e.getMessage(), true));
        } finally {
            synchronized (handle) {
                handle.workerThread = null;
                handle.accepting = false;
            }
            runs.remove(runId, handle);
            try {
                runRepository.finished(runId, status, java.time.Instant.now());
            } catch (RuntimeException e) {
                log.warn("记录 run {} 结束状态失败", runId, e);
            }
            if (runStates != null) {
                runStates.finish(sessionId, runId, status);
            }
            if (!resultCaptured) {
                captureResult(sessionId, runId);
            }
            Deque<String> queue = inbox(sessionId);
            try {
                synchronized (queue) {
                    // 释放槽位和启动排队消息必须连在一起，HTTP 新请求不能抢进交接窗口。
                    registry.unregister(runId);
                    if (!continued && TaskControlStatus.COMPLETED.is(status) && !handle.cancelled.get()
                        && plans.load(sessionId).stream().allMatch(com.pilot.core.plan.PlanItem::done)) {
                        drainQueue(sessionId, queue);
                    }
                }
            } catch (RuntimeException e) {
                log.error("会话 {} 的排队消息交接失败，保留队列等待重试", sessionId, e);
            } finally {
                metrics.recordRun(status, System.currentTimeMillis() - startedAt);
            }
        }
    }

    private void captureResult(String sessionId, String runId) {
        try {
            taskControls.snapshotResult(sessionId, runId);
        } catch (RuntimeException e) {
            log.warn("保存运行 {} 的结果快照失败", runId, e);
        }
    }

    @Override
    public java.util.Optional<String> claimRun(String sessionId) {
        synchronized (inbox(sessionId)) {
            String runId = "run_" + java.util.UUID.randomUUID();
            return registry.claim(runId, sessionId, sessionWorkspace(sessionId)) ? java.util.Optional.of(runId)
                : java.util.Optional.empty();
        }
    }

    private Path sessionWorkspace(String sessionId) {
        return toolFactory.resolve(sessions.find(sessionId).map(Session::workspace).orElse(null));
    }

    @Override
    public boolean lockWorkspace(String operationId, Path workspace) {
        if (commandTasks.hasRunningInWorkspace(workspace) || !registry.lockWorkspace(operationId, workspace)) {
            return false;
        }
        if (commandTasks.hasRunningInWorkspace(workspace)) {
            registry.unregister(operationId);
            return false;
        }
        return true;
    }

    @Override
    public void abandon(String runId) {
        registry.unregister(runId);
    }

    @Override
    public void forget(String sessionId) {
        registry.forget(sessionId);
        inboxes.remove(sessionId);
        autoContinueAttempts.remove(sessionId);
        autoContinueSignature.remove(sessionId);
        try {
            queuedMessages.save(sessionId, List.of());
            queuedMessages.save("steer:" + sessionId, List.of());
        } catch (RuntimeException e) {
            log.warn("清理会话 {} 的排队消息失败（会话可能已被删除）", sessionId, e);
        }
        if (runStates != null) {
            try {
                runStates.forget(sessionId);
            } catch (RuntimeException ignored) {
                // 删除会话时的清理失败不应阻断删除本身
            }
        }
    }

    /** 当前任务完整结束后，在队列锁内占位并启动下一条；失败、暂停、计划未完成时不消费。 */
    private void drainQueue(String sessionId, Deque<String> queue) {
        String raw = queue.peek();
        if (raw == null) {
            return;
        }
        QueuedMessageCodec.Stored next = QueuedMessageCodec.decode(raw);
        String runId = claimRun(sessionId).orElse(null);
        if (runId == null) {
            return;
        }
        try {
            sessions.publish(sessionId, runId,
                new RunStarted(next.content(), next.attachments(), next.imagePositions()));
            launch(sessionId, runId, next.content(), next.attachments(), next.imagePositions(), false, null);
        } catch (RuntimeException e) {
            registry.unregister(runId);
            publish(sessionId, runId, new RunFailed(RunFailureCode.QUEUE_START_FAILED, "排队消息启动失败，消息已保留", false));
            throw e;
        }
        queue.remove();
        persistQueue(sessionId, queue);
    }

    @Override
    public void requirementsUpdated(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            TaskControlService.TaskContract task = taskControls.load(sessionId);
            if (task == null || !TaskControlStatus.COMPLETED.is(task.status())
                || registry.activeRun(sessionId).isPresent()
                || plans.load(sessionId).stream().anyMatch(item -> !item.done())) {
                return;
            }
            try {
                drainQueue(sessionId, queue);
            } catch (RuntimeException e) {
                log.error("验收后的排队消息启动失败，队列已保留：{}", sessionId, e);
            }
        }
    }

    /**
     * 步数触顶时，如果计划里还有未完成项就自动接上下一轮。
     *
     * <p>两道刹车：最多连续 {@value #MAX_AUTO_CONTINUE} 次；计划状态连续两轮没有变化即视为卡住，停下等人工介入。
     */
    private boolean maybeAutoContinue(String sessionId, String finishedRunId, String status, RunHandle handle) {
        if (!RunFailureCode.MAX_STEPS.is(status) || handle.cancelled.get()
            || runStates != null && runStates.leaseLost(finishedRunId)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        List<PlanItem> items = plans.load(sessionId);
        if (items.stream().allMatch(com.pilot.core.plan.PlanItem::done)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }

        String signature = items.stream().map(item -> item.step() + "=" + item.status())
            .collect(java.util.stream.Collectors.joining("|"));
        if (signature.equals(autoContinueSignature.get(sessionId))) {
            // 计划两轮没变，说明模型在原地打转
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        int attempt = autoContinueAttempts.merge(sessionId, 1, Integer::sum);
        if (attempt > MAX_AUTO_CONTINUE) {
            autoContinueAttempts.remove(sessionId);
            return false;
        }

        autoContinueSignature.put(sessionId, signature);
        String runId = "run_" + java.util.UUID.randomUUID();
        publish(sessionId, runId, new RunStarted("（继续上一次未完成的任务）"));
        publish(sessionId, runId, new RunContinued(attempt, MAX_AUTO_CONTINUE, "步数触顶，按未完成计划继续"));
        launch(sessionId, runId, null, List.of(), true);
        return true;
    }

    @Override
    public java.util.Optional<String> activeRun(String sessionId) {
        return registry.activeRun(sessionId);
    }

    /** 重新生成：先丢掉上一轮对话（含最后一条用户消息），再用同样的输入重跑，避免历史重复。 */
    @Override
    public void regenerate(String sessionId, String runId, String input) {
        TaskControlService.TaskContract task = taskControls.load(sessionId);
        String originalInput = task == null ? input : task.originalGoal();
        List<Message> history = conversations.load(sessionId);
        int lastUser = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role()) && originalInput.equals(history.get(i).content())) {
                lastUser = i;
                break;
            }
        }
        if (lastUser >= 0) {
            conversations.save(sessionId, history.subList(0, lastUser));
        }
        launch(sessionId, runId, originalInput, List.of(), List.of(), false, null, task != null, null);
    }

    @Override
    public boolean cancel(String runId) {
        RunHandle handle = runs.get(runId);
        if (handle == null) {
            return false;
        }
        synchronized (handle) {
            handle.accepting = false;
            handle.cancelled.set(true);
            Thread worker = handle.workerThread;
            if (worker != null) {
                worker.interrupt();
            }
        }
        handle.pendingApprovals.forEach(callId -> approvals.resolve(callId, false));
        log.info("Run {} cancel requested", runId);
        return true;
    }

    /**
     * 组装本轮 system：正文全部来自 {@code prompts/} 资源，这里只注入环境与当前计划。
     *
     * <p>计划每轮重新注入：压缩会折叠历史，但 system 是重建的，长期约束不会丢
     * （与 Claude Code 把约束放 CLAUDE.md 的思路一致）。计划与日期排在 system 最尾：
     * 越动态的段越靠后，前缀缓存按 token 匹配，尾部变化只损失尾部缓存。
     *
     * <p>正文段落按 {@code availableTools} 过滤：依赖的工具没注册，对应段落不进提示词，
     * 避免教模型调用不存在的工具。仓库快照不进 system（每轮 git status 变化会断全部缓存），
     * 改在会话首条 run 时作为 user 消息注入一次。
     */
    private String systemPrompt(String sessionId, String workspace, Set<String> availableTools, SkillCatalog catalog,
        ReviewMode review) {
        return systemPromptParts(sessionId, workspace, availableTools, catalog, review).stream()
            .map(ContextComposition.Source::text).collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private List<ContextComposition.Source> systemPromptParts(String sessionId, String workspace,
        Set<String> availableTools, SkillCatalog catalog, ReviewMode review) {
        Map<String, String> variables = new java.util.LinkedHashMap<>(PromptLibrary.environment(workspace));
        variables.put("skills", skills.catalogText(catalog));
        List<ContextComposition.Source> parts = new ArrayList<>(PROMPTS.systemParts(variables, availableTools));
        String rules = projectRules(workspace);
        if (!rules.isEmpty()) {
            parts.add(new ContextComposition.Source("rules", rules));
        }
        String memory = ProjectRuleLoader.loadMemory(toolFactory.resolve(workspace), PROMPTS);
        if (!memory.isEmpty()) {
            parts.add(new ContextComposition.Source("rules", memory));
        }
        if (review.active()) {
            parts.add(new ContextComposition.Source("system",
                PROMPTS.render("review/protocol", Map.of("scope", review.scopeText())).strip()));
        }
        return List.copyOf(parts);
    }

    private String currentTaskContext(String sessionId) {
        return taskControls.prompt(sessionId) + "\n历史目标或验收范围不明时，先 read_task_history 恢复用户原文，不能按摘要自行缩小范围。"
            + commandContext(sessionId) + "\n\n"
            + PROMPTS.render("system/today", Map.of("today", java.time.LocalDate.now().toString())).strip() + "\n\n"
            + planBrief(sessionId);
    }

    private static void addNativeToolNames(Set<String> names, Set<com.pilot.core.model.NativeCapability> capabilities) {
        if (capabilities.contains(com.pilot.core.model.NativeCapability.WEB_SEARCH)) {
            names.add(ToolNames.WEB_SEARCH);
        }
        if (capabilities.contains(com.pilot.core.model.NativeCapability.WEB_FETCH)) {
            names.add(ToolNames.WEB_FETCH);
        }
    }

    /**
     * 审查 run 的只读工具表：复用子代理的 scope 裁剪机制（同一条硬隔离路径），
     * 写文件与派发工具对模型「不存在」而不是「被拒绝」，避免无谓的重试浪费。
     * 命令保留：git 只读子命令在内置白名单里，写命令照旧走权限门。
     */
    private ToolRegistry reviewTools(String workspace) {
        AgentScope scope = AgentScope.readOnly(List.of(), List.of()).withTools(List.of("read_*", "list_*", "glob_*",
            "search_*", ToolNames.RUN_COMMAND, ToolNames.WEB_SEARCH, ToolNames.WEB_FETCH), List.of());
        return toolFactory.registryForScope(workspace, scope, com.pilot.core.tool.SandboxSink.rejectAll());
    }

    /**
     * 本轮会话的完整工具名单：基础工具集 + 本方法调用点下方按条件注册的
     * {@code spawn_agent} / {@code dispatch_agents}。系统提示词据此过滤依赖段。
     *
     * <p>条件注册的工具在这里声明第二遍是权衡的结果：SpawnAgentTool 持有 config，
     * 注册无法提前到 systemPrompt 之前；谁改注册条件，谁同步改这里。
     */
    private Set<String> sessionTools(ToolRegistry base) {
        Set<String> names = new java.util.LinkedHashSet<>(base.names());
        names.add(ToolNames.SPAWN_AGENT);
        if (multiProperties.enabled()) {
            names.add(ToolNames.DISPATCH_AGENTS);
        }
        return Set.copyOf(names);
    }

    /**
     * 会话首条 run 时把仓库快照作为第一条 user 消息注入一次，此后不再注入。
     *
     * <p>快照不进 system：git 状态每轮都会变，进 system 会让跨轮前缀缓存全部失效；
     * 作为首条 user 消息随历史正常落盘与压缩，模板已声明「以工具实查为准」。
     */
    private List<Message> withRepositoryBrief(List<Message> history, String workspace) {
        if (!history.isEmpty()) {
            return history;
        }
        String brief = repositoryBrief(workspace);
        if (brief.isEmpty()) {
            return history;
        }
        List<Message> seeded = new ArrayList<>(history);
        seeded.add(Message.user(brief));
        return List.copyOf(seeded);
    }

    /**
     * 用户显式点名技能时，把技能指引作为独立 user 消息注入在用户输入之前（与仓库快照同模式）。
     *
     * <p>不进 system：500 行正文会破坏跨轮前缀缓存，而 user 消息随历史可压缩、续跑不丢；
     * 激活语义（用户指定了什么）本就属于对话流。会话启动到运行之间技能被删除时注入说明，不静默忽略。
     */
    private List<Message> withSkillActivation(List<Message> history, String workspace, RunHandle handle) {
        if (handle.skill == null) {
            return history;
        }
        String activate = skills.activateText(skills.catalogFor(workspace), handle.skill);
        if (activate == null) {
            activate = "（用户指定了技能 " + handle.skill + "，但该技能当前不可用：未找到或校验未通过。）";
        }
        List<Message> seeded = new ArrayList<>(history);
        seeded.add(Message.user(activate));
        return List.copyOf(seeded);
    }

    /**
     * 仓库现状快照：顶层结构、git 分支与未提交改动、构建方式。
     *
     * <p>模型不用从零摸索，也不会覆盖用户手上的未提交改动。
     * 仅在会话首条 run 时生成一次，此后模型按提示词要求用工具实查。
     */
    private String repositoryBrief(String workspace) {
        Path root;
        try {
            root = toolFactory.resolve(workspace);
        } catch (RuntimeException e) {
            return "";
        }
        if (!Files.isDirectory(root)) {
            return "";
        }
        try {
            return PROMPTS.render("context/repository",
                Map.of("tree", workspaceTree(root), "git", gitState(root), "build", buildHint(root))).strip();
        } catch (RuntimeException e) {
            log.debug("生成仓库摘要失败", e);
            return "";
        }
    }

    private static String workspaceTree(Path root) {
        try (Stream<Path> entries = Files.list(root)) {
            List<String> names = entries.map(path -> {
                String name = path.getFileName().toString();
                return Files.isDirectory(path) ? name + "/" : name;
            }).filter(
                name -> !IGNORED_ENTRIES.contains(name.endsWith("/") ? name.substring(0, name.length() - 1) : name))
                .sorted().limit(MAX_TREE_ENTRIES + 1L).toList();
            if (names.isEmpty()) {
                return "（空目录）";
            }
            boolean truncated = names.size() > MAX_TREE_ENTRIES;
            String listed = String.join("\n", names.stream().limit(MAX_TREE_ENTRIES).map(name -> "- " + name).toList());
            return truncated ? listed + "\n- …（其余条目省略）" : listed;
        } catch (IOException e) {
            return "（读取目录失败）";
        }
    }

    private static String gitState(Path root) {
        if (!Files.isDirectory(root.resolve(".git"))) {
            return "不是 git 仓库";
        }
        String branch = branchName(root);
        String status = runGit(root, "status", "--porcelain=v1", "-uall");
        if (status == null) {
            return "分支 `" + branch + "`（无法读取未提交改动）";
        }
        List<String> lines = status.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return "分支 `" + branch + "`，工作区干净";
        }
        String listed =
            lines.stream().limit(MAX_GIT_CHANGES).map(line -> "- " + line.strip()).collect(Collectors.joining("\n"));
        String more = lines.size() > MAX_GIT_CHANGES ? "\n- …（还有 " + (lines.size() - MAX_GIT_CHANGES) + " 个）" : "";
        return "分支 `" + branch + "`，有 " + lines.size() + " 个文件未提交：\n" + listed + more;
    }

    private static String branchName(Path root) {
        try {
            String head = Files.readString(root.resolve(".git").resolve("HEAD"), StandardCharsets.UTF_8).strip();
            String prefix = "ref: refs/heads/";
            if (head.startsWith(prefix)) {
                return head.substring(prefix.length());
            }
            return head.isEmpty() ? "unknown" : "detached@" + head.substring(0, Math.min(7, head.length()));
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String runGit(Path root, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        try {
            Process process = builder.start();
            if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String buildHint(Path root) {
        String childNodeProjects;
        try (Stream<Path> children = Files.list(root)) {
            childNodeProjects =
                children.filter(Files::isDirectory).filter(path -> Files.isRegularFile(path.resolve("package.json")))
                    .map(path -> path.getFileName().toString()).sorted().limit(8).collect(Collectors.joining("、"));
        } catch (IOException e) {
            childNodeProjects = "";
        }
        String nodeHint = childNodeProjects.isEmpty() ? ""
            : "；Node 子项目：" + childNodeProjects + "（先读各自 package.json，运行 npm 命令时设置对应 workdir）";
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return "Maven 项目：优先用工作区的 `mvnw`，不要依赖系统 maven" + nodeHint;
        }
        if (Files.isRegularFile(root.resolve("build.gradle"))
            || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return "Gradle 项目：用工作区的 `gradlew`";
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return "Node 项目：先看 package.json 的 scripts，再跑 `npm test` / `npm run build` 这类脚本" + nodeHint;
        }
        if (Files.isRegularFile(root.resolve("pyproject.toml"))
            || Files.isRegularFile(root.resolve("requirements.txt"))) {
            return "Python 项目：用 `pytest` 或项目里声明的命令";
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            return "Rust 项目：`cargo test` / `cargo build`";
        }
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            return "Go 项目：`go build ./...` / `go test ./...`";
        }
        return "未识别到根目录构建文件，改动后按项目实际情况验证" + nodeHint;
    }

    /** 把工作区和祖先目录的项目约定注入 system，并保留每份文件的来源。 */
    private String projectRules(String workspace) {
        Path root;
        try {
            root = toolFactory.resolve(workspace);
        } catch (RuntimeException e) {
            return "";
        }
        return ProjectRuleLoader.load(root, PROMPTS);
    }

    private String planBrief(String sessionId) {
        List<PlanItem> items = plans.load(sessionId);
        if (items.isEmpty()) {
            return "";
        }
        List<PlanItem> open = items.stream().filter(item -> !item.done()).toList();
        if (open.isEmpty()) {
            return PROMPTS.render("system/plan-done", Map.of()).strip();
        }
        String list =
            open.stream().map(item -> "- [" + item.status() + "] " + item.step()).collect(Collectors.joining("\n"));
        return PROMPTS.render("system/plan-context", Map.of("planItems", list)).strip();
    }

    private String execute(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, RunHandle handle, boolean continuation) {
        List<Message> history = conversations.load(sessionId);
        ModelClient runModel = modelClient.snapshot();
        Set<com.pilot.core.model.NativeCapability> nativeCapabilities = runModel.nativeCapabilities();
        String workspace = sessions.find(sessionId).map(Session::workspace).orElse(null);
        // /review 独立审查模式：只读工具表 + 固定审查协议，写文件与派发工具在本 run 里不存在。
        // 权限语义不变（只读命令照常放行，写命令照旧审批），不新加档位也不影响普通 run。
        TaskControlService.TaskContract executionTask = taskControls.load(sessionId);
        boolean unattended = executionTask != null && executionTask.automation() != null;
        ReviewMode review = ReviewMode.parse(unattended ? executionTask.originalGoal() : input);
        PlanSink planSink = (items, explanation) -> {
            plans.save(sessionId, items);
            publish(sessionId, runId,
                new PlanUpdated(
                    items.stream().map(item -> new PlanUpdated.PlanItemPayload(item.step(), item.status())).toList(),
                    explanation));
        };
        ToolRegistry tools =
            review.active() ? reviewTools(workspace) : sessionTools(sessionId, runId, workspace, planSink);
        // 本地工具保留供同一轮自动降级；原生客户端只在请求序列化时隐藏对应定义。
        // 初始目录用于系统提示词；use_skill 每次读取当前目录，让本轮新安装的技能立即可用。
        SkillCatalog catalog = skills.catalogFor(workspace);
        if (!review.active() && (!catalog.list().isEmpty() || (skills.enabled() && skillInstaller != null))) {
            tools.register(new UseSkillTool(() -> skills.catalogFor(workspace)));
        }
        // 条件注册的 spawn_agent / dispatch_agents 位于 config 构造之后，先算出最终工具名，
        // 让 system prompt 只注入本轮真实可用的工具说明。
        // 审查 run 用 reviewTools 的最终名单：它既不注册也不允许派发工具，
        // 不能再补 spawn_agent / dispatch_agents，否则提示词会介绍本轮根本调不到的工具。
        if (unattended && scheduleService != null) {
            tools.register(scheduleService.getObject().wakeTool(sessionId));
        }
        Set<String> availableTools =
            new java.util.LinkedHashSet<>(review.active() ? tools.names() : sessionTools(tools));
        addNativeToolNames(availableTools, nativeCapabilities);
        RunUsageAccumulator runUsage = new RunUsageAccumulator();
        // 收尾事件里的检查摘要要用于校验证据，sink 里先留一份
        AtomicReference<RunCompleted> lastCompleted = new AtomicReference<>();
        // 事件类型计数：压缩次数、工具调用量、失败码分布都从这里来
        Consumer<SessionEvent> sink = runEventSink(sessionId, runId, handle, planSink, runUsage, lastCompleted);
        Consumer<List<Message>> checkpoint = messages -> conversations.save(sessionId, messages);
        List<ContextComposition.Source> promptSources =
            systemPromptParts(sessionId, workspace, availableTools, catalog, review);
        Consumer<RequestReport> reports = report -> {
            synchronized (runUsage) {
                publish(sessionId, runId, new ModelRequestCompleted(report));
                publish(sessionId, runId, runUsage.request(report));
            }
        };
        ModelClient measuredMain = new MeteredModelClient(runModel, sessionId, "main", promptSources, reports);
        ModelClient measuredChild =
            new MeteredModelClient(runModel, sessionId + ":subagent", "subagent", List.of(), reports);
        AgentLoop.Config config =
            new AgentLoop.Config(properties.maxSteps(), properties.repeatRemindAt(), properties.repeatAbortAt(),
                promptSources.stream().map(ContextComposition.Source::text)
                    .collect(java.util.stream.Collectors.joining("\n\n")),
                // 收尾时读一次计划，把「本轮是否真的做完」一并写进 run.completed
                () -> plans.load(sessionId),
                // 计划没做完时自动继续，而不是停下来等用户点「继续执行」
                properties.maxPlanContinues(), properties.contextMaxChars(), properties.completionChecksEnabled());
        GoalExecutionPolicy goalPolicy = (unattended || (!review.active() && properties.goalModeEnabledValue()))
            ? new GoalExecutionPolicy(measuredMain, objectMapper, taskControls, sessionId,
                toolFactory.resolve(workspace), handle.cancelled::get, properties.maxSteps(),
                properties.directMaxRequests(), properties.directMaxInputTokens(), properties.directMaxOutputTokens(),
                properties.directMaxActiveMillis(), properties.directMaxInvestigationTools(),
                properties.goalMaxInputTokens(), properties.goalMaxOutputTokens(), properties.goalMaxActiveMillis())
            : null;
        if (goalPolicy != null) {
            goalPolicy.withCommandLookup(id -> commandTasks.get(sessionId, id));
            goalPolicy.withAvailableTools(availableTools)
                .withUpdates(state -> sink.accept(new com.pilot.core.event.ExecutionUpdated(state.mode(), state.phase(),
                    state.requests(), state.reason(), state.gap())));
            tools.register(goalPolicy.promotionTool());
            if (taskControls.load(sessionId).automation() != null) {
                tools.register(goalPolicy.researchTool());
            }
        }
        ModelClient executionClient = goalPolicy == null ? measuredMain : goalPolicy.budgetedClient();
        // 子代理在构造时冻结并过滤工具快照，使用 run 级共享池；请求/usage 仍计入父任务事故熔断，
        // reviewer/finalization 的额外预留不对工作型子请求开放。
        SpawnAgentTool.UsageSink childUsage = childUsageSink(goalPolicy, runUsage, sessionId, runId);
        // 用户创建自动任务时单独授权全工具访问；普通任务仍沿用工作区 allow/deny/ask。
        // 自动任务的调研门禁在 ExecutionPolicy 中执行，不被这里的工具权限绕过。
        PermissionGate sessionGate = unattended && executionTask.automation().fullAccess()
            ? (call, tool) -> com.pilot.core.permission.PermissionResult.allow()
            : withWorkspaceRules(permissionGate, toolFactory.resolve(workspace));
        // 本 run 可用的自定义代理目录（两级扫描，热更新）；测试未注入时为空目录
        AgentProfileCatalog profiles =
            agentProfiles == null ? AgentProfileCatalog.EMPTY : agentProfiles.catalogFor(workspace);
        // 审查 run 不派子代理：单一视角审到底，也避免派发工具把只读裁剪绕出去。
        if (!review.active()) {
            tools.register(new SpawnAgentTool(measuredChild, tools, sessionGate, approvals, config,
                () -> conversations.load(sessionId), sink, handle.cancelled::get, childUsage, profiles));
            // 多智能体调度：父 Agent 只能派活，子 Agent 的改动一律进沙箱
            if (multiProperties.enabled()) {
                tools.register(new DispatchAgentsTool(multiAgent, sessionId, runId, workspace, sink,
                    handle.cancelled::get, executionClient, profiles, childUsage)
                    .withParentPermission(unattended && executionTask.automation().fullAccess()
                        ? com.pilot.core.permission.PermissionMode.AUTO : null));
            }
        }
        // 与会话数据库、快照保持一致，统一落在数据目录（默认项目下的 .pilot）
        JsonlTraceRecorder traceRecorder =
            new JsonlTraceRecorder(dataDir.file("traces", sessionId + ".jsonl"), objectMapper);
        AgentLoop loop = new AgentLoop(executionClient, tools, sessionGate, approvals, config, handle.cancelled::get,
            () -> null, traceRecorder, delta -> sessions.preview(sessionId, runId, new AssistantPreview(delta)))
            .withPendingMessages(() -> pollSteering(sessionId))
            .withLiveReasoning(
                delta -> sessions.preview(sessionId, runId, new com.pilot.core.event.AssistantReasoning(delta)))
            .withTaskContext(() -> currentTaskContext(sessionId))
            .withCompletionClaim(() -> claimCompletion(sessionId, handle)).withRestoredPaths(snapshots::restoredPaths)
            // 编辑后自动格式化/lint 之类交给 .pilot/settings.json，不再占用模型注意力
            .withHooks(com.pilot.core.hook.Hooks.load(toolFactory.resolve(workspace)))
            .withHookExecution(toolFactory.executionSelection())
            // 重启接管后重复的写文件调用不重复落盘
            .withToolJournal(runId, journals == null ? com.pilot.core.tool.ToolCallJournal.none()
                : journals.forWorkspace(sessionId, toolFactory.resolve(workspace)));

        if (goalPolicy != null) {
            loop.withExecutionPolicy(goalPolicy);
        }
        snapshots.bind(runId, toolFactory.resolve(workspace));
        try {
            // 普通运行时，会话首条消息前注入一次仓库快照（见 withRepositoryBrief）；续跑不注入；
            // 用户点名了技能时，指引作为独立 user 消息在用户输入之前注入（见 withSkillActivation）
            TaskControlService.TaskContract activeTask = taskControls.load(sessionId);
            String completionGoal = activeTask == null ? null : activeTask.originalGoal();
            AgentLoop.Result result = continuation ? loop.resume(history, sink, checkpoint, completionGoal)
                : loop.run(input, attachments, imagePositions,
                    withSkillActivation(withRepositoryBrief(history, workspace), workspace, handle), sink, checkpoint);
            conversations.save(sessionId, result.conversation());
            log.info("Agent run {} completed: failureCode={}, messages={}, workspace={}", runId, result.failureCode(),
                result.conversation().size(), workspace == null ? properties.workspace() : workspace);
            if (result.failureCode() == null) {
                recordValidation(sessionId, runId, workspace, lastCompleted.get());
                TaskControlService.TaskContract task = taskControls.load(sessionId);
                return task == null ? TaskControlStatus.COMPLETED.name() : task.status();
            }
            if (handle.paused) {
                return TaskControlStatus.PAUSED.name();
            }
            // 步数触顶要单独返回，外层才会按未完成计划自动接下一轮。
            // 以前一律收成 FAILED，自动继续的判断永远进不去。
            if (preservedAsTaskStatus(result.failureCode())) {
                return result.failureCode();
            }
            if (WireEnum.in(result.failureCode(), RunFailureCode.CANCELLED, RunFailureCode.MAX_STEPS)) {
                return result.failureCode();
            }
            return TaskControlStatus.FAILED.name();
        } catch (RuntimeException e) {
            log.error("Agent run {} failed unexpectedly", runId, e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            taskControls.status(sessionId, TaskControlStatus.FAILED);
            finishSnapshots(runId);
            publish(sessionId, runId, new RunFailed(RunFailureCode.INTERNAL_ERROR, "执行失败：" + reason, false));
            return TaskControlStatus.FAILED.name();
        } finally {
            try {
                finishSnapshots(runId);
            } finally {
                snapshots.clear();
            }
            com.pilot.server.desktop.DesktopViewportReset reset = viewportReset;
            if (reset != null) {
                reset.clear(sessionId);
            }
        }
    }

    private void finishSnapshots(String runId) {
        try {
            snapshots.finish();
        } catch (RuntimeException e) {
            log.warn("完成运行快照失败 {}", runId, e);
        }
    }

    private SpawnAgentTool.UsageSink childUsageSink(GoalExecutionPolicy goalPolicy, RunUsageAccumulator runUsage,
        String sessionId, String runId) {
        SpawnAgentTool.UsageSink taskChildUsage =
            goalPolicy == null ? SpawnAgentTool.UsageSink.none() : goalPolicy.subagentUsageSink();
        return new SpawnAgentTool.UsageSink() {
            @Override
            public void beforeRequest() {
                taskChildUsage.beforeRequest();
            }

            @Override
            public void afterRequest(long activeMillis, ModelUsage usage) {
                try {
                    taskChildUsage.afterRequest(activeMillis, usage);
                } finally {
                    RunUsageUpdated merged = runUsage.child(usage);
                    metrics.count("event." + merged.type().wireName());
                    publish(sessionId, runId, merged);
                }
            }
        };
    }

    /** 将运行事件转成任务状态和持久回执；预览片段不进入这条持久化链路。 */
    private Consumer<SessionEvent> runEventSink(String sessionId, String runId, RunHandle handle, PlanSink planSink,
        RunUsageAccumulator runUsage, AtomicReference<RunCompleted> lastCompleted) {
        return event -> {
            // 预览只给当前界面，不写会话历史：正文以 assistant.delta 为准，重连后也不该回放临时片段。
            if (event instanceof AssistantPreview || event instanceof AssistantReasoning) {
                sessions.preview(sessionId, runId, event);
                return;
            }
            if (event instanceof RunCompleted completed) {
                lastCompleted.set(completed);
            }
            if (event instanceof ApprovalRequired approval) {
                handle.pendingApprovals.add(approval.callId());
                if (handle.cancelled.get() || !queuedMessages.load("steer:" + sessionId).isEmpty()) {
                    approvals.resolve(approval.callId(), false);
                }
            } else if (event instanceof ApprovalResolved approval) {
                handle.pendingApprovals.remove(approval.callId());
            }
            SessionEvent delivered = event instanceof RunUsageUpdated usage ? runUsage.main(usage) : event;
            if (event instanceof RunCancelled) {
                var stoppedTask = taskControls.load(sessionId);
                taskControls.status(sessionId,
                    handle.paused
                        || (handle.interruptedReason == null && stoppedTask != null && stoppedTask.automation() != null)
                            ? TaskControlStatus.PAUSED : TaskControlStatus.INTERRUPTED);
                if (handle.paused) {
                    delivered = new RunFailed(RunFailureCode.PAUSED, "任务已暂停，目标、约束、验收条件和排队消息已保留。", true);
                } else if (handle.interruptedReason != null) {
                    delivered = new RunFailed(RunFailureCode.INTERRUPTED, handle.interruptedReason, true);
                }
            } else if (event instanceof RunFailed failed) {
                taskControls.status(sessionId, failureTaskStatus(failed.code()));
            } else if (event instanceof RunCompleted completed) {
                delivered = completedEvent(sessionId, planSink, completed);
            }
            metrics.count("event." + delivered.type().wireName());
            if (delivered instanceof RunCompleted || delivered instanceof RunFailed
                || delivered instanceof RunCancelled) {
                // 收到终止事件的客户端会立即请求本轮补丁，先保存完成态。
                finishSnapshots(runId);
            }
            // 事件落库同时推进可恢复检查点，重启后能说清「停在哪、下一步做什么」
            if (runStates != null) {
                runStates.onEvent(sessionId, runId, delivered);
            }
            publish(sessionId, runId, delivered);
        };
    }

    private String failureTaskStatus(String code) {
        if (RunFailureCode.MAX_STEPS.is(code) && properties.goalModeEnabledValue()) {
            return TaskControlStatus.BUDGET_LIMITED.name();
        }
        return preservedAsTaskStatus(code) ? code : TaskControlStatus.INTERRUPTED.name();
    }

    private static boolean preservedAsTaskStatus(String code) {
        return WireEnum.parse(TaskControlStatus.class, code).filter(PRESERVED_FAILURE_STATUSES::contains).isPresent();
    }

    private RunCompleted completedEvent(String sessionId, PlanSink planSink, RunCompleted completed) {
        TaskControlService.TaskContract verified = taskControls.load(sessionId);
        boolean goalVerified =
            verified != null && verified.execution() != null && ExecutionMode.GOAL.is(verified.execution().mode())
                && ExecutionPhase.VERIFIED.is(verified.execution().phase());
        RunCompleted effective = completed;
        if (goalVerified) {
            planSink.update(List.of(), "目标已核验完成，清理执行计划");
            if (!completed.openSteps().isEmpty()) {
                effective = new RunCompleted(completed.summary(), completed.changedFiles(), completed.checks(),
                    completed.planTotal(), completed.planTotal(), List.of());
            }
        }
        // 显式未完成的计划保留为待复核，不能直接把任务置为已完成。
        TaskControlService.TaskContract task = effective.openSteps().isEmpty() ? taskControls.finish(sessionId)
            : taskControls.status(sessionId, TaskControlStatus.AWAITING_REVIEW);
        RunCompleted delivered = effective;
        if (task != null && TaskControlStatus.AWAITING_REVIEW.is(task.status())) {
            ArrayList<String> open = new ArrayList<>(effective.openSteps());
            task.acceptance().stream().filter(item -> !item.checked()).forEach(item -> open.add("待验收：" + item.text()));
            delivered = new RunCompleted(effective.summary(), effective.changedFiles(), effective.checks(),
                effective.planDone() + (int)task.acceptance().stream().filter(item -> item.checked()).count(),
                effective.planTotal() + task.acceptance().size(), open);
        }
        return delivered;
    }

    /**
     * 把「本轮成功」绑定到当前产物指纹、实际检查命令和环境。
     *
     * <p>没有产物变化（只回答问题）时不写证据：那不构成对代码的完成声明。
     * 有变化但没有任何检查记录时记为 {@code skipped}，界面会显示「未经校验」，而不是通过。
     */
    private void recordValidation(String sessionId, String runId, String workspace, RunCompleted completed) {
        if (validations == null) {
            return;
        }
        try {
            RunSnapshotService.SnapshotMeta meta = snapshots.meta(runId);
            List<String> files =
                meta == null ? List.of() : meta.files().stream().map(RunSnapshotService.SnapshotEntry::path).toList();
            String candidate =
                com.pilot.server.validation.WorkspaceFingerprint.of(toolFactory.resolve(workspace), files);
            if (candidate.isBlank()) {
                return;
            }
            List<String> checks = completed == null ? List.of() : completed.checks();
            TaskControlService.TaskContract task = taskControls.load(sessionId);
            String phase =
                task == null || task.execution() == null ? ExecutionPhase.UNVERIFIED.name() : task.execution().phase();
            // 使用已绑定当前源码版本的账本判断，不能把旧检查或普通 exit 0 重新包装成通过。
            boolean qualifying =
                checks.stream().anyMatch(check -> com.pilot.core.tool.VerificationCheck.passingKind(check).qualifies());
            String status = ExecutionPhase.CHECKS_FAILED.is(phase) ? ValidationEvidence.FAILED
                : qualifying && WireEnum.in(phase, ExecutionPhase.CHECKS_PASSED, ExecutionPhase.VERIFIED)
                    ? ValidationEvidence.PASSED : ValidationEvidence.SKIPPED;
            validations.record(new ValidationEvidence("val_" + java.util.UUID.randomUUID(), sessionId, runId, candidate,
                files, checks, status, com.pilot.server.multi.SandboxStore.environmentFingerprint(), "run",
                java.time.Instant.now()));
        } catch (RuntimeException e) {
            log.debug("记录校验证据失败：session={}, run={}", sessionId, runId, e);
        }
    }

    /**
     * UI 的 run.usage 是“当前主循环快照”，子循环的局部累计不能直接转发，否则会把父数值倒退。
     * 这里保存最后一份主快照并叠加子请求增量，两条来源都只在本 run 内存在。
     */
    static final class RunUsageAccumulator {
        private RunUsageUpdated main = new RunUsageUpdated(0, 0, 0, 0, 1, false);
        private int childRequests;
        private long childInputTokens;
        private long childOutputTokens;
        private long childCachedInputTokens;
        private boolean childExact = true;
        private final RequestTotals observed = new RequestTotals();
        private int latestContext;

        synchronized RunUsageUpdated request(RequestReport report) {
            observed.add(report);
            if ("main".equals(report.purpose())) {
                latestContext = (int)Math.min(Integer.MAX_VALUE, report.context().totalTokens());
            }
            return combined();
        }

        synchronized RunUsageUpdated main(RunUsageUpdated usage) {
            main = Objects.requireNonNull(usage);
            return combined();
        }

        synchronized RunUsageUpdated child(ModelUsage usage) {
            ModelUsage value = usage == null ? ModelUsage.unknown() : usage;
            childRequests++;
            childInputTokens += value.inputTokens();
            childOutputTokens += value.outputTokens();
            childCachedInputTokens += value.cachedInputTokens();
            childExact &= value.exact();
            return combined();
        }

        private RunUsageUpdated combined() {
            RequestTotals.Summary summary = observed.summary();
            if (summary.requests() > 0) {
                return new RunUsageUpdated(summary.requests(), observed.input(), observed.output(),
                    summary.cacheReadTokens(), latestContext > 0 ? latestContext : main.contextTokens(),
                    main.contextLimit(), summary.exact(), summary);
            }
            return new RunUsageUpdated(main.modelRequests() + childRequests, main.inputTokens() + childInputTokens,
                main.outputTokens() + childOutputTokens, main.cachedInputTokens() + childCachedInputTokens,
                main.contextTokens(), main.contextLimit(), main.exact() && childExact);
        }
    }

    private static final class RunHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Set<String> pendingApprovals = ConcurrentHashMap.newKeySet();
        private final String sessionId;
        /** 用户显式点名的技能；仅本次 run 注入，自动继续/排队/重放不继承。 */
        private final String skill;
        private volatile boolean accepting = true;
        private volatile boolean paused;
        /** 非用户取消的中断原因（如服务重启）；为空表示普通取消/暂停。 */
        private volatile String interruptedReason;
        private volatile Future<?> future;
        volatile Thread workerThread;

        private RunHandle(String sessionId, String skill) {
            this.sessionId = sessionId;
            this.skill = skill == null || skill.isBlank() ? null : skill.strip();
        }
    }

    /** 会话命令有独立的任务归属；默认和受限子代理工具集不共享这个入口。 */
    private ToolRegistry sessionTools(String sessionId, String runId, String workspace, PlanSink planSink) {
        ToolRegistry tools = new ToolRegistry();
        toolFactory.registryFor(workspace, planSink).tools().stream()
            .filter(tool -> !ToolNames.RUN_COMMAND.equals(tool.name())).forEach(tools::register);
        tools.register(
            new RunCommandTool(new PathValidator(toolFactory.resolve(workspace)), commandTasks, sessionId, snapshots));
        tools.register(new CommandStatusTool(commandTasks, sessionId, snapshots));
        tools.register(new CommandLogsTool(commandTasks, sessionId));
        tools.register(new StopCommandTool(commandTasks, sessionId));
        tools.register(new ReadTaskHistoryTool(sessions, sessionId));
        if (workProducts != null) {
            tools.register(new com.pilot.server.work.PublishArtifactTool(workProducts, objectMapper, sessionId, runId));
        }
        if (skillDrafts != null && skills != null && skills.enabled()) {
            tools.register(new com.pilot.server.work.ProposeSkillTool(skillDrafts, objectMapper, sessionId, runId));
        }
        if (skillInstaller != null && skills != null && skills.enabled()) {
            tools.register(new InstallSkillTool(skillInstaller, skills, objectMapper, workspace));
        }
        // 浏览器与 MCP：始终注册，是否可见由工具自己的 availability 在运行时决定。
        toolFactory.sessionExtras(sessionId, workspace).forEach(tools::register);
        return tools;
    }

    private String commandContext(String sessionId) {
        List<CommandTaskManager.Task> tasks = commandTasks.list(sessionId);
        if (tasks.isEmpty()) {
            return "";
        }
        return "\n\n本会话最近的命令任务（后台命令独立运行，已启动不代表验证通过）：\n"
            + tasks.stream().limit(10)
                .map(task -> "- " + task.taskId() + " [" + task.status() + "] " + Texts.oneLine(task.command(), 180)
                    + (task.exitCode() == null ? "" : "，退出码 " + task.exitCode()))
                .collect(Collectors.joining("\n"))
            + "\n用 command_status 确认实际退出结果、command_logs 查看增量日志，必要时 stop_command 停止进程。";
    }

    private void publish(String sessionId, String runId, SessionEvent event) {
        try {
            sessions.publish(sessionId, runId, event);
        } catch (RuntimeException e) {
            log.warn("Dropped event {} for session {}", event.type().wireName(), sessionId, e);
        }
    }

    @PostConstruct
    void logConfiguration() {
        ModelSettings.Snapshot snapshot = modelSettings.snapshot();
        if (!snapshot.hasApiKey()) {
            log.warn("模型 API Key 未配置，模型请求会失败；" + "可在界面「模型设置」里填写，或设置环境变量 PILOT_MODEL_API_KEY");
        } else {
            log.info("模型已配置：model={} endpoint={} workspace={}", snapshot.model(), snapshot.endpoint(),
                properties.workspace());
        }
    }

    @PreDestroy
    void shutdown() {
        // 先按「服务重启」取消所有在跑的任务，再中断线程：模型请求被 interrupt 后会走取消分支，
        // 而不是被记成 MODEL_ERROR「模型调用失败」。进度仍会落盘，重启后可继续。
        for (RunHandle handle : runs.values()) {
            handle.interruptedReason = "Pilot 服务正在重启或关闭，本轮已中断；进度已保存，恢复后可继续执行。";
            handle.cancelled.set(true);
        }
        executor.shutdownNow();
    }
}
