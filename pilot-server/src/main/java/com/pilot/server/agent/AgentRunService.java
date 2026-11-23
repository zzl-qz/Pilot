package com.pilot.server.agent;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
import com.pilot.core.event.RunEnded;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.RunOutcome;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.ContextCompactor;
import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.RequestReport;
import com.pilot.core.model.RequestTotals;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.quality.IndependentCodeReviewer;
import com.pilot.core.quality.WorkspaceTexts;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.core.trace.JsonlTraceRecorder;
import com.pilot.core.util.WireEnum;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.model.ModelSettings;
import com.pilot.server.multi.MultiAgentService;
import com.pilot.server.multi.SpawnAgentTool;
import com.pilot.server.run.EvidenceStatus;
import com.pilot.server.run.ExecutionMode;
import com.pilot.server.run.ExecutionPhase;
import com.pilot.server.run.ReviewMode;
import com.pilot.server.run.RunRegistry;
import com.pilot.server.run.RunStarter;
import com.pilot.server.run.RunStateTracker;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.run.TaskControlStatus;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.tool.WorkspaceToolFactory;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 在独立线程上执行 {@link AgentLoop}，把循环产生的事件写入会话。
 *
 * <p>对话记忆通过 {@code ConversationRepository} 落盘（默认 SQLite），每步结束都会 checkpoint，
 * 因此进程重启后可以用 {@code /continue} 接着跑；计划通过 {@code PlanRepository} 保存，
 * 并在每次运行时重新注入 system，保证压缩之后计划仍然可见。
 */
@Component
@org.springframework.context.annotation.DependsOn("historyEditCoordinator")
@Slf4j
public class AgentRunService implements RunStarter {
    private static final int MAX_AUTO_CONTINUE = Integer.getInteger("pilot.agent.max-auto-continue", 8);
    /**
     * 预算触顶不是失败：一次大型任务可以分几个预算段继续，像 DSH 的 goal round 一样
     * 每段重新计数。上限只防失控，不把生产任务卡在第一次触顶。
     */
    private static final int MAX_BUDGET_SEGMENTS = Integer.getInteger("pilot.agent.max-budget-segments", 8);
    /**
     * 目标还没核验完时，步数触顶也在同一会话里再开一轮。
     * dsh 默认最多 256 个 goal round，而且不要求计划先发生变化。
     * 我们每一轮自己还有步数上限，外层再给若干轮（默认 32），避免一次触顶就把长任务停死。
     */
    private static final int MAX_GOAL_ROUNDS = Integer.getInteger("pilot.agent.max-goal-rounds", 32);
    /** 这些失败码与任务状态同名：运行停下后任务直接记为同名状态，不归并成 INTERRUPTED。 */
    private static final Set<TaskControlStatus> PRESERVED_FAILURE_STATUSES =
        Set.of(TaskControlStatus.BLOCKED, TaskControlStatus.BUDGET_LIMITED, TaskControlStatus.WAITING,
            TaskControlStatus.NEED_INPUT, TaskControlStatus.UNVERIFIED, TaskControlStatus.NO_PROGRESS,
            TaskControlStatus.REVIEW_UNAVAILABLE, TaskControlStatus.VERIFICATION_FAILED);
    private final RepositoryContext repositoryContext;
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
    /** 会话级已启用的延迟工具，按激活顺序保存，让续跑与追问沿用同一个尾部扩展段。 */
    private final AgentRunPreparation preparation;
    private final DeferredToolRegistry deferredTools;
    private final ObjectMapper objectMapper;
    private final PilotDataDir dataDir;
    private final MultiAgentService multiAgent;
    private final MultiAgentProperties multiProperties;
    private final com.pilot.server.observability.RunMetrics metrics;
    private com.pilot.server.git.GitWorktrees worktrees;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorktrees(com.pilot.server.git.GitWorktrees worktrees) {
        this.worktrees = worktrees;
    }

    /** 工作区协调事件投影；轻量装配（测试）不注入时不发布，也不影响运行。 */
    private volatile com.pilot.server.coordination.CoordinationEventRelay coordinationEvents;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCoordinationEvents(com.pilot.server.coordination.CoordinationEventRelay coordinationEvents) {
        this.coordinationEvents = coordinationEvents;
    }

    /** 可恢复调度状态；为空时（测试构造）不做检查点，也不影响执行。 */
    private final RunStateTracker runStates;
    private final com.pilot.server.store.ToolCallJournalFactory journals;
    private final com.pilot.server.validation.ValidationLedger validations;
    /** 证据过期判断：运行结束时用它决定要不要撤销人工验收勾选。 */
    private final com.pilot.server.validation.ValidationStatusService validationStatus;
    /** 验证策略门禁；测试轻量装配不注入时，保持现有行为。 */
    private volatile com.pilot.server.validation.VerificationPolicyService verificationPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.pilot.server.schedule.ScheduleService> scheduleService;
    private volatile com.pilot.server.desktop.DesktopViewportReset viewportReset;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setViewportReset(com.pilot.server.desktop.DesktopViewportReset viewportReset) {
        this.viewportReset = viewportReset;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVerificationPolicies(com.pilot.server.validation.VerificationPolicyService verificationPolicies) {
        this.verificationPolicies = verificationPolicies;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
        PermissionGate permissionGate, ApprovalBroker approvals, AgentProperties properties,
        ModelSettings modelSettings, RunSnapshotService snapshots, RunRegistry registry,
        com.pilot.core.session.RunRepository runRepository, ConversationRepository conversations,
        com.pilot.core.session.PlanRepository plans, ObjectMapper objectMapper, PilotDataDir dataDir,
        com.pilot.server.store.QueuedMessageRepository queuedMessages, MultiAgentService multiAgent,
        MultiAgentProperties multiProperties, com.pilot.server.observability.RunMetrics metrics,
        TaskControlService taskControls, CommandTaskManager commandTasks, AgentRunPreparation preparation,
        DeferredToolRegistry deferredTools, RunStateTracker runStates,
        com.pilot.server.store.ToolCallJournalFactory journals,
        com.pilot.server.validation.ValidationLedger validations) {
        this.sessions = sessions;
        this.runStates = runStates;
        this.journals = journals;
        this.validations = validations;
        // 结论计算本身无状态，只是账本的一层判断，不必再进构造器参数表。
        this.validationStatus =
            validations == null ? null : new com.pilot.server.validation.ValidationStatusService(validations);
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
        this.repositoryContext = new RepositoryContext(properties, toolFactory,
            new RepositoryBriefCache(dataDir == null ? null : dataDir.file("repository-briefs")));
        this.queuedMessages = queuedMessages;
        this.multiAgent = multiAgent;
        this.multiProperties = multiProperties;
        this.metrics = metrics;
        this.preparation = preparation;
        this.deferredTools = deferredTools;
        this.executor = Executors.newFixedThreadPool(properties.runThreads(), runnable -> {
            Thread thread = new Thread(runnable, "pilot-agent-run");
            thread.setDaemon(true);
            return thread;
        });
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
        long wire = com.pilot.core.model.ModelCapacity.active().inputBudget();
        long local = (long)(properties.contextMaxChars() * 0.80);
        int limit = (int)Math.max(1, Math.min(local, wire));
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
        return followup(sessionId, text, attachments, imagePositions, null);
    }

    @Override
    public boolean followup(String sessionId, String text, List<Attachment> attachments, List<Integer> imagePositions,
        RunStarter.QueueRequirements requirements) {
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
            queue.offer(QueuedMessageCodec
                .encode(new QueuedMessageCodec.Stored(content, attachments, imagePositions, requirements)));
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
        return queue.stream().map(QueuedMessageCodec::decode).map(
            item -> new QueuedMessage(item.content(), item.attachments(), item.imagePositions(), item.requirements()))
            .toList();
    }

    @Override
    public boolean editQueued(String sessionId, int index, String text, String revision) {
        return editQueued(sessionId, index, text, null, revision);
    }

    @Override
    public boolean editQueued(String sessionId, int index, String text, RunStarter.QueueRequirements requirements,
        String revision) {
        return updateQueue(sessionId, revision, items -> {
            if (text == null || text.isBlank() || index < 0 || index >= items.size()) {
                return false;
            }
            // 只改文字与要求，图片附件跟着这条消息走；requirements 为 null 表示保留原要求。
            QueuedMessageCodec.Stored current = QueuedMessageCodec.decode(items.get(index));
            QueuedMessageCodec.Stored next = current.withContent(text.strip());
            if (requirements != null) {
                next = next.withRequirements(requirements.empty() ? null : requirements);
            }
            items.set(index, QueuedMessageCodec.encode(next));
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
                if (!continuation && !regenerate && previous != null && TaskControlStatus.WAITING.is(previous.status())
                    && (requirements == null
                        || requirements.constraints().isEmpty() && requirements.acceptance().isEmpty())) {
                    // 交接/等待用户的任务：新消息是对同一任务的继续，不能覆盖原始目标与验收。
                    taskControls.correct(sessionId, input);
                } else if (!continuation && !regenerate) {
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
                    if (continuation && TaskControlStatus.BUDGET_LIMITED.is(previous.status())) {
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
            BudgetProgress before = BudgetProgress.from(plans.load(sessionId), taskControls.load(sessionId));
            PreparedRunContext runContext = prepareExecutionContext(sessionId, runId, input, handle, continuation);
            ExecutionRunContext execution =
                configureExecutionContext(sessionId, runId, runContext, handle.cancelled::get);
            status = execute(sessionId, runId, input, attachments, imagePositions, handle, continuation, execution);
            captureResult(sessionId, runId);
            resultCaptured = true;
            continued = maybeAutoContinue(sessionId, runId, status, handle, before);
        } catch (Throwable e) {
            log.error("Run {} 收尾失败", runId, e);
            taskControls.status(sessionId, TaskControlStatus.FAILED);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            publish(sessionId, runId,
                new RunFailed(RunFailureCode.INTERNAL_ERROR, "执行失败：" + detail, !(e instanceof Error)));
            if (e instanceof Error error && !(e instanceof StackOverflowError) && !(e instanceof OutOfMemoryError)) {
                throw error;
            }
        } finally {
            synchronized (handle) {
                handle.workerThread = null;
                handle.accepting = false;
            }
            runs.remove(runId, handle);
            // 部分交付不是失败：runs 表记成 INTERRUPTED，和 checkpoint 的可恢复语义一致。
            String recordedStatus = RunFailureCode.TASK_INCOMPLETE.is(status) ? "INTERRUPTED" : status;
            try {
                runRepository.finished(runId, recordedStatus, java.time.Instant.now());
            } catch (RuntimeException e) {
                log.warn("记录 run {} 结束状态失败", runId, e);
            }
            if (runStates != null) {
                runStates.finish(sessionId, runId, recordedStatus);
            }
            if (!resultCaptured) {
                captureResult(sessionId, runId);
            }
            Deque<String> queue = inbox(sessionId);
            try {
                synchronized (queue) {
                    // 释放槽位和启动排队消息必须连在一起，HTTP 新请求不能抢进交接窗口。
                    registry.unregister(runId);
                    if (!continued
                        && (TaskControlStatus.COMPLETED.is(status) || RunFailureCode.TASK_INCOMPLETE.is(status))
                        && !handle.cancelled.get() && (RunFailureCode.TASK_INCOMPLETE.is(status)
                            || plans.load(sessionId).stream().noneMatch(com.pilot.core.plan.PlanItem::open))) {
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
    public Optional<String> workspaceOccupant(Path workspace) {
        List<CommandTaskManager.Task> commands = commandTasks.runningInWorkspace(workspace);
        if (!commands.isEmpty()) {
            String owners = commands.stream().map(task -> task.taskId() + "（会话 " + task.sessionId() + "）")
                .collect(Collectors.joining("、"));
            return Optional.of("后台命令 " + owners);
        }
        return registry.occupier(workspace).map(occupant -> "会话 " + occupant.sessionId() + " 的运行 " + occupant.runId());
    }

    @Override
    public void abandon(String runId) {
        registry.unregister(runId);
    }

    @Override
    public boolean queueHeld(String sessionId) {
        return queuedMessages.held(sessionId);
    }

    @Override
    public boolean setQueueHeld(String sessionId, boolean held) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            boolean current = queuedMessages.held(sessionId);
            if (current == held) {
                return true;
            }
            queuedMessages.setHeld(sessionId, held);
            if (!held) {
                // 空闲且验收已满足时只启动队首一次；运行中则等它结束时由 drainQueue 接手
                resumeHeldQueue(sessionId, queue);
            }
            return true;
        }
    }

    /** 恢复后的启动尝试：条件不满足就什么都不做，等运行结束或验收完成时的常规交接。 */
    private void resumeHeldQueue(String sessionId, Deque<String> queue) {
        if (registry.activeRun(sessionId).isPresent()) {
            return;
        }
        TaskControlService.TaskContract task = taskControls.load(sessionId);
        if (task == null || !TaskControlStatus.COMPLETED.is(task.status())
            || plans.load(sessionId).stream().anyMatch(com.pilot.core.plan.PlanItem::open)) {
            return;
        }
        try {
            drainQueue(sessionId, queue);
        } catch (RuntimeException e) {
            log.error("恢复队列后启动队首失败，队列已保留：{}", sessionId, e);
        }
    }

    @Override
    public String queueWaitingReason(String sessionId) {
        if (queuedMessages.held(sessionId)) {
            return "队列已暂停，恢复后才会开始下一条";
        }
        if (registry.activeRun(sessionId).isPresent()) {
            return "等待本轮结束";
        }
        TaskControlService.TaskContract task = taskControls.load(sessionId);
        if (task == null || !TaskControlStatus.COMPLETED.is(task.status())
            || plans.load(sessionId).stream().anyMatch(com.pilot.core.plan.PlanItem::open)) {
            return "等待验收";
        }
        return "";
    }

    @Override
    public void forget(String sessionId) {
        registry.forget(sessionId);
        inboxes.remove(sessionId);
        autoContinueAttempts.remove(sessionId);
        autoContinueSignature.remove(sessionId);
        deferredTools.forget(sessionId);
        try {
            queuedMessages.clearHold(sessionId);
        } catch (RuntimeException e) {
            log.warn("清理会话 {} 的队列暂停状态失败", sessionId, e);
        }
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
        // 手动暂停时保留队列；当前运行照常继续，恢复后再走同一条启动路径
        if (queuedMessages.held(sessionId)) {
            return;
        }
        int pick = -1;
        int index = 0;
        for (String candidate : queue) {
            if (QueueLane.sendsAutomatically(QueuedMessageCodec.decode(candidate).content())) {
                pick = index;
                break;
            }
            index++;
        }
        if (pick < 0) {
            return;
        }
        String raw = null;
        int seen = 0;
        for (String candidate : queue) {
            if (seen == pick) {
                raw = candidate;
                break;
            }
            seen++;
        }
        QueuedMessageCodec.Stored next = QueuedMessageCodec.decode(raw);
        String runId = claimRun(sessionId).orElse(null);
        if (runId == null) {
            return;
        }
        try {
            sessions.publish(sessionId, runId,
                new RunStarted(next.content(), next.attachments(), next.imagePositions()));
            TaskControlService.Requirements requirements =
                next.requirements() == null ? null : next.requirements().toRequirements();
            launch(sessionId, runId, next.content(), next.attachments(), next.imagePositions(), false, requirements);
        } catch (RuntimeException e) {
            registry.unregister(runId);
            publish(sessionId, runId, new RunFailed(RunFailureCode.QUEUE_START_FAILED, "排队消息启动失败，消息已保留", false));
            throw e;
        }
        java.util.Iterator<String> remaining = queue.iterator();
        for (int cursor = 0; cursor <= pick; cursor++) {
            remaining.next();
            if (cursor == pick) {
                remaining.remove();
            }
        }
        persistQueue(sessionId, queue);
    }

    @Override
    public void requirementsUpdated(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            TaskControlService.TaskContract task = taskControls.load(sessionId);
            if (task == null || !TaskControlStatus.COMPLETED.is(task.status())
                || registry.activeRun(sessionId).isPresent()
                || plans.load(sessionId).stream().anyMatch(com.pilot.core.plan.PlanItem::open)) {
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
     * 步数/预算触顶时接上下一轮。普通任务看计划有没有动；目标任务还没核验完就在同一会话里继续。
     *
     * <p>普通任务最多连续 8 次，计划连续两轮没变就停下。
     * 普通任务预算触顶时，只有本段留下新进展才自动开下一段；用户仍可手动继续。
     * 预算最多续 8 段，目标任务步数触顶最多续 32 轮。
     */
    private boolean maybeAutoContinue(String sessionId, String finishedRunId, String status, RunHandle handle,
        BudgetProgress before) {
        boolean budgetLimited = RunFailureCode.BUDGET_LIMITED.is(status);
        if (!RunFailureCode.MAX_STEPS.is(status) && !budgetLimited || handle.cancelled.get()
            || runStates != null && runStates.leaseLost(finishedRunId)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        boolean goalOpen = goalStillOpen(sessionId);
        List<PlanItem> items = plans.load(sessionId);
        if (!goalOpen && items.stream().noneMatch(com.pilot.core.plan.PlanItem::open)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        TaskControlService.TaskContract current = taskControls.load(sessionId);
        int segments = current == null || current.execution() == null ? 0 : current.execution().budgetSegments();
        String signature = items.stream().map(item -> item.step() + "=" + item.status())
            .collect(java.util.stream.Collectors.joining("|"));
        if (!goalOpen && (budgetLimited ? !BudgetProgress.from(items, current).advancedFrom(before)
            : signature.equals(autoContinueSignature.get(sessionId)))) {
            log.info("运行 {} 没有新增进展，停止自动续跑", finishedRunId);
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        int cap = budgetLimited ? MAX_BUDGET_SEGMENTS : goalOpen ? MAX_GOAL_ROUNDS : MAX_AUTO_CONTINUE;
        int attempt = budgetLimited ? segments + 1 : autoContinueAttempts.merge(sessionId, 1, Integer::sum);
        if (attempt > cap) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }

        autoContinueSignature.put(sessionId, signature);
        String runId = "run_" + java.util.UUID.randomUUID();
        publish(sessionId, runId, new RunStarted("（继续上一次未完成的任务）"));
        String reason = budgetLimited ? "预算分段继续第 " + attempt + "/" + cap + " 段"
            : goalOpen ? "目标尚未核验，同一会话继续第 " + attempt + " 轮" : "步数触顶，按未完成计划继续";
        publish(sessionId, runId, new RunContinued(attempt, cap, reason));
        launch(sessionId, runId, null, List.of(), true);
        return true;
    }

    private record BudgetProgress(Set<String> completedSteps, Set<String> changedPaths, Set<List<String>> checks) {
        static BudgetProgress from(List<PlanItem> plan, TaskControlService.TaskContract task) {
            Set<String> done = plan.stream().filter(PlanItem::done).map(PlanItem::step).collect(Collectors.toSet());
            TaskControlService.Execution execution = task == null ? null : task.execution();
            if (execution == null) {
                return new BudgetProgress(done, Set.of(), Set.of());
            }
            // 同一个检查对同一份源码得到同样的结果，即使重跑时间、任务 ID 不同，也不算新证据。
            Set<List<String>> checks = GoalEvidence.latestChecks(execution.evidence()).entrySet().stream()
                .filter(entry -> WireEnum.in(entry.getValue().status(), EvidenceStatus.SUCCESS,
                    EvidenceStatus.PERMANENT_FAILURE))
                .map(entry -> List.of(entry.getKey(), entry.getValue().endVersion(), entry.getValue().status()))
                .collect(Collectors.toSet());
            return new BudgetProgress(done, Set.copyOf(execution.changedPaths()), checks);
        }

        boolean advancedFrom(BudgetProgress before) {
            return !before.completedSteps.containsAll(completedSteps) || !before.changedPaths.containsAll(changedPaths)
                || !before.checks.containsAll(checks);
        }
    }

    /** 目标模式且还没核验通过、也不是在等用户时，步数用完不应结束整个任务。 */
    private boolean goalStillOpen(String sessionId) {
        if (!properties.goalModeEnabledValue()) {
            return false;
        }
        TaskControlService.TaskContract task = taskControls.load(sessionId);
        if (task == null || task.execution() == null || !ExecutionMode.GOAL.is(task.execution().mode())) {
            return false;
        }
        if (ExecutionPhase.VERIFIED.is(task.execution().phase())
            || WireEnum.in(task.execution().phase(), ExecutionPhase.NEED_INPUT, ExecutionPhase.WAIT,
                ExecutionPhase.BLOCKED, ExecutionPhase.IMPOSSIBLE, ExecutionPhase.NO_PROGRESS)) {
            return false;
        }
        return !WireEnum.in(task.status(), TaskControlStatus.COMPLETED, TaskControlStatus.NEED_INPUT,
            TaskControlStatus.WAITING, TaskControlStatus.BLOCKED);
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

    private PreparedRunContext prepareExecutionContext(String sessionId, String runId, String input, RunHandle handle,
        boolean continuation) {
        List<Message> history = conversations.load(sessionId);
        Set<com.pilot.core.model.NativeCapability> nativeCapabilities = modelClient.nativeCapabilities();
        TaskControlService.TaskContract executionTask = taskControls.load(sessionId);
        boolean unattended = executionTask != null && executionTask.automation() != null;
        AtomicReference<AgentLoop> activeLoopRef = new AtomicReference<>();
        AtomicReference<Path> resolvedWorkspaceRef = new AtomicReference<>();
        AtomicReference<PathValidator> workspaceValidatorRef = new AtomicReference<>();
        AtomicReference<List<PlanItem>> previousPlanRef = new AtomicReference<>(plans.load(sessionId));
        PlanSink planSink = (items, explanation) -> {
            List<PlanItem> beforePlan = previousPlanRef.getAndSet(items);
            plans.save(sessionId, items);
            publish(sessionId, runId,
                new PlanUpdated(items.stream()
                    .map(item -> new PlanUpdated.PlanItemPayload(item.step(), item.status(), item.note())).toList(),
                    explanation));
            preparation.refreshPlanPhaseHint(activeLoopRef.get(), resolvedWorkspaceRef.get(),
                workspaceValidatorRef.get(), beforePlan, items);
        };
        AgentRunPreparation.PreparationInput preparationInput =
            new AgentRunPreparation.PreparationInput(sessionId, runId, input, handle.skill, continuation, history,
                unattended, executionTask == null ? null : executionTask.originalGoal(),
                executionTask != null && executionTask.automation() != null && executionTask.automation().fullAccess(),
                nativeCapabilities, deferredTools.snapshot(sessionId));
        AgentRunPreparation.PreparedRun prepared = preparation.prepare(preparationInput, planSink, tools -> {
            if (unattended && scheduleService != null) {
                tools.register(scheduleService.getObject().wakeTool(sessionId));
            }
        });
        resolvedWorkspaceRef.set(prepared.resolvedWorkspace());
        workspaceValidatorRef.set(prepared.workspaceValidator());
        PermissionGate sessionGate = unattended && executionTask.automation().fullAccess()
            ? (call, tool) -> com.pilot.core.permission.PermissionResult.allow()
            : preparation.withWorkspaceRules(permissionGate, toolFactory.resolve(prepared.workspace()));
        List<Message> activatedHistory = continuation ? history
            : preparation.activateSkill(repositoryContext.withRepositoryBrief(history, prepared.workspace(), sessionId),
                prepared.workspace(), handle.skill);
        AtomicReference<PreFlightLocator.Result> preflight = new AtomicReference<>();
        Consumer<AgentLoop> configurePreflight =
            loop -> preflight.set(preparation.configurePreflight(loop, prepared, input, continuation));
        Consumer<AgentLoop.Result> logPreflightTelemetry = result -> preparation.logPreflightTelemetry(runId,
            preflight.get(), activeLoopRef.get().observedPathsSnapshot(), result.changedFiles());
        RunUsageAccumulator runUsage = new RunUsageAccumulator();
        AtomicReference<RunCompleted> lastCompleted = new AtomicReference<>();
        Consumer<SessionEvent> sink = runEventSink(sessionId, runId, handle, planSink, runUsage, lastCompleted);
        return new PreparedRunContext(history, executionTask, unattended, planSink, prepared, sessionGate,
            preparation.readGuardOf(prepared.tools()), activeLoopRef, configurePreflight, activatedHistory,
            logPreflightTelemetry, preparation.taskContextSource(sessionId), runUsage, lastCompleted, sink);
    }

    private ExecutionRunContext configureExecutionContext(String sessionId, String runId, PreparedRunContext runContext,
        java.util.function.BooleanSupplier cancelled) {
        var prepared = runContext.prepared();
        boolean unattended = runContext.unattended();
        TaskControlService.TaskContract executionTask = runContext.executionTask();
        String workspace = prepared.workspace();
        boolean reviewActive = prepared.review().active();
        RunUsageAccumulator runUsage = runContext.runUsage();
        AtomicReference<RunCompleted> lastCompleted = runContext.lastCompleted();
        Consumer<SessionEvent> sink = runContext.sink();
        Consumer<RequestReport> reports = report -> {
            synchronized (runUsage) {
                publish(sessionId, runId, new ModelRequestCompleted(report));
                publish(sessionId, runId, runUsage.request(report));
            }
        };
        ModelClient measuredMain = preparation.meteredMainClient(prepared, modelClient, sessionId, runId, reports);
        ModelClient measuredChild =
            new MeteredModelClient(modelClient, sessionId + ":subagent", "subagent", List.of(), reports)
                .withRunContext(sessionId, runId);
        AgentLoop.Config config = preparation.loopConfig(prepared, sessionId);
        GoalExecutionPolicy goalPolicy = (unattended || (!reviewActive && properties.goalModeEnabledValue()))
            ? new GoalExecutionPolicy(measuredMain, objectMapper, taskControls, sessionId,
                toolFactory.resolve(workspace), cancelled, properties.maxSteps(), properties.directMaxRequests(),
                properties.directMaxInputTokens(), properties.directMaxOutputTokens(),
                properties.directMaxActiveMillis(), properties.directMaxInvestigationTools(),
                properties.goalMaxInputTokens(), properties.goalMaxOutputTokens(), properties.goalMaxActiveMillis())
            : null;
        ModelClient executionClient = goalPolicy == null ? measuredMain : goalPolicy.budgetedClient();
        SpawnAgentTool.UsageSink childUsage = childUsageSink(goalPolicy, runUsage, sessionId, runId);
        var profiles = preparation.profilesFor(workspace);
        preparation.configureExecutionTools(prepared, goalPolicy, sessionId, runId, workspace, unattended,
            executionTask, measuredChild, executionClient, runContext.sessionGate(), config, cancelled, childUsage,
            profiles, modelClient, sink, approvals, conversations, modelSettings, multiAgent, worktrees);
        return new ExecutionRunContext(runContext, runUsage, lastCompleted, sink, measuredMain, config, goalPolicy,
            executionClient);
    }

    private String execute(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, RunHandle handle, boolean continuation, ExecutionRunContext execution) {
        PreparedRunContext runContext = execution.prepared();
        RunUsageAccumulator runUsage = execution.runUsage();
        AtomicReference<RunCompleted> lastCompleted = execution.lastCompleted();
        Consumer<SessionEvent> sink = execution.sink();
        ModelClient measuredMain = execution.measuredMain();
        AgentLoop.Config config = execution.config();
        GoalExecutionPolicy goalPolicy = execution.goalPolicy();
        ModelClient executionClient = execution.executionClient();
        List<Message> history = runContext.history();
        var prepared = runContext.prepared();
        AtomicReference<AgentLoop> activeLoopRef = runContext.activeLoopRef();
        String workspace = prepared.workspace();
        Path resolvedWorkspace = prepared.resolvedWorkspace();
        ReviewMode review = prepared.review();
        ToolRegistry tools = prepared.tools();
        Consumer<List<Message>> checkpoint = messages -> conversations.save(sessionId, messages);
        PermissionGate sessionGate = runContext.sessionGate();
        // 与会话数据库、快照保持一致，统一落在数据目录（默认项目下的 .pilot）
        JsonlTraceRecorder traceRecorder =
            new JsonlTraceRecorder(dataDir.file("traces", sessionId + ".jsonl"), objectMapper);
        AgentLoop loop = new AgentLoop(executionClient, tools, sessionGate, approvals, config, handle.cancelled::get,
            () -> null, traceRecorder, delta -> sessions.preview(sessionId, runId, new AssistantPreview(delta)))
            .withPendingMessages(() -> pollSteering(sessionId))
            .withLiveReasoning(
                delta -> sessions.preview(sessionId, runId, new com.pilot.core.event.AssistantReasoning(delta)))
            .withTaskContext(runContext.taskContextSource())
            .withCompletionClaim(() -> claimCompletion(sessionId, handle)).withRestoredPaths(snapshots::restoredPaths)
            .withQualityWorkspace(resolvedWorkspace)
            .withQualityTexts(snapshots::originalText, path -> WorkspaceTexts.read(resolvedWorkspace, path))
            .withLintGate(properties.lintGate())
            .withScopedRules(paths -> com.pilot.server.agent.ProjectRuleLoader.scoped(resolvedWorkspace, paths,
                com.pilot.core.prompt.PromptLibrary.standard()))
            .withIndependentCodeReviewer(review.active() ? null
                : new IndependentCodeReviewer(measuredMain, resolvedWorkspace, handle.cancelled::get))
            // 编辑后自动格式化/lint 之类交给 .pilot/settings.json，不再占用模型注意力
            .withHooks(com.pilot.core.hook.Hooks.load(toolFactory.resolve(workspace)))
            .withHookExecution(toolFactory.executionSelection())
            // 重启接管后重复的写文件调用不重复落盘
            .withToolJournal(runId, journals == null ? com.pilot.core.tool.ToolCallJournal.none()
                : journals.forWorkspace(sessionId, toolFactory.resolve(workspace)));

        com.pilot.core.tool.ReadGuard readGuard = runContext.readGuard();
        if (readGuard != null) {
            loop.withContextShrinkListener(readGuard::invalidateDeliveredViews);
        }
        runContext.configurePreflight().accept(loop);
        activeLoopRef.set(loop);

        if (goalPolicy != null) {
            loop.withExecutionPolicy(goalPolicy);
        }
        snapshots.bind(runId, toolFactory.resolve(workspace));
        try {
            // 工作区变了就在本步输入前补一份仓库快照（见 withRepositoryBrief），没变不重复注入；
            // 用户点名了技能时，指引作为独立 user 消息在用户输入之前注入（见 withSkillActivation）
            TaskControlService.TaskContract activeTask = taskControls.load(sessionId);
            String completionGoal = activeTask == null ? null : activeTask.originalGoal();
            AgentLoop.Result result = continuation ? loop.resume(history, sink, checkpoint, completionGoal)
                : loop.run(input, attachments, imagePositions, runContext.activatedHistory(), sink, checkpoint);
            conversations.save(sessionId, result.conversation());
            runContext.logPreflightTelemetry().accept(result);
            log.info("Agent run {} completed: failureCode={}, messages={}, workspace={}", runId, result.failureCode(),
                result.conversation().size(), workspace == null ? properties.workspace() : workspace);
            // 失败/部分交付也要把已经跑过的检查记进证据账本；不能因为收尾是 TASK_INCOMPLETE
            // 就让用户看到「还没有任何校验证据」。只有取消/启动失败这类没有产物的终态跳过。
            if (!WireEnum.in(result.failureCode(), RunFailureCode.CANCELLED, RunFailureCode.START_FAILED)) {
                RunCompleted completed = lastCompleted.get();
                List<String> checks = completed == null ? result.checks() : completed.checks();
                recordValidation(sessionId, runId, workspace, checks, result.failureCode() == null);
            }
            if (result.failureCode() == null) {
                TaskControlService.TaskContract task = taskControls.load(sessionId);
                return task == null ? TaskControlStatus.COMPLETED.name() : task.status();
            }
            if (handle.paused) {
                return TaskControlStatus.PAUSED.name();
            }
            // 部分交付不是失败：任务状态由 RunEnded 的 outcome 落到 AWAITING_REVIEW，
            // 外层只需要把这个码原样带回，让 runOnWorker 映射成可恢复的 run 状态。
            if (RunFailureCode.TASK_INCOMPLETE.is(result.failureCode())) {
                return result.failureCode();
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
            if (!review.active()) {
                deferredTools.remember(sessionId, tools.enabledDeferredTools());
            }
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
                taskControls.settleDirectRun(sessionId, handle.paused ? RunOutcome.PAUSED : RunOutcome.CANCELLED,
                    handle.interruptedReason == null ? "运行已停止" : handle.interruptedReason);
                if (handle.paused) {
                    delivered = new RunFailed(RunFailureCode.PAUSED, "任务已暂停，目标、约束、验收条件和排队消息已保留。", true);
                } else if (handle.interruptedReason != null) {
                    delivered = new RunFailed(RunFailureCode.INTERRUPTED, handle.interruptedReason, true);
                }
            } else if (event instanceof RunFailed failed) {
                taskControls.status(sessionId, failureTaskStatus(failed.code()));
                taskControls.settleDirectRun(sessionId, RunOutcome.fromCode(failed.code()), failed.message());
            } else if (event instanceof RunEnded ended) {
                // 部分交付/阻塞/等用户：任务状态由 outcome 派生，不再走 FAILED。
                taskControls.status(sessionId, ended.outcome().taskStatus());
                taskControls.settleDirectRun(sessionId, ended.outcome(), ended.summary());
            } else if (event instanceof RunCompleted completed) {
                delivered = completedEvent(sessionId, planSink, completed);
            }
            metrics.count("event." + delivered.type().wireName());
            if (delivered instanceof RunCompleted || delivered instanceof RunFailed || delivered instanceof RunCancelled
                || delivered instanceof RunEnded) {
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
        if (RunFailureCode.TASK_INCOMPLETE.is(code)) {
            return TaskControlStatus.AWAITING_REVIEW.name();
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
     * 这次运行让旧证据失效时，撤销人工验收勾选。
     *
     * <p>验收勾选代表「我认可这份结果」。这一轮又改了文件，之前的认可就不再指向当前内容；
     * 保留勾选等于替用户担保，所以撤下来并要求重新核对。原验收文字与证据照旧保留。
     */
    private void revokeAcceptanceIfEvidenceStale(String sessionId, String workspace) {
        if (validationStatus == null || taskControls == null) {
            return;
        }
        var snapshot = validationStatus.snapshot(sessionId, toolFactory.resolve(workspace), null);
        if (!snapshot.stale()) {
            return;
        }
        int revoked = taskControls.revokeAcceptanceForStaleEvidence(sessionId);
        if (revoked > 0) {
            log.info("校验证据过期，已撤销 {} 条验收勾选：session={}", revoked, sessionId);
        }
    }

    /**
     * 把本轮产物绑定到当前指纹、实际检查命令和环境。
     *
     * <p>没有产物变化（只回答问题）时不写证据：那不构成对代码的完成声明。
     * 有变化但没有任何检查记录时记为 {@code skipped}，界面会显示「未经校验」，而不是通过。
     *
     * <p>失败/部分交付也要调用：已经跑过的检查是事实，不能因为收尾事件不是 completed 就丢掉。
     * 只有真正成功的终态才执行策略门禁，避免把「部分交付、还没验证」再改写成 VERIFICATION_FAILED。
     */
    private void recordValidation(String sessionId, String runId, String workspace, List<String> checks,
        boolean enforcePolicy) {
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
            List<String> effectiveChecks = checks == null ? List.of() : checks;
            TaskControlService.TaskContract task = taskControls.load(sessionId);
            String phase =
                task == null || task.execution() == null ? ExecutionPhase.UNVERIFIED.name() : task.execution().phase();
            // 使用已绑定当前源码版本的账本判断，不能把旧检查或普通 exit 0 重新包装成通过。
            boolean qualifying = effectiveChecks.stream()
                .anyMatch(check -> com.pilot.core.tool.VerificationCheck.passingKind(check).qualifies());
            String status = ExecutionPhase.CHECKS_FAILED.is(phase) ? ValidationEvidence.FAILED
                : qualifying ? ValidationEvidence.PASSED : ValidationEvidence.SKIPPED;
            validations.record(new ValidationEvidence("val_" + java.util.UUID.randomUUID(), sessionId, runId, candidate,
                files, effectiveChecks, status, com.pilot.server.multi.SandboxStore.environmentFingerprint(), "run",
                java.time.Instant.now()));
            revokeAcceptanceIfEvidenceStale(sessionId, workspace);
            if (enforcePolicy) {
                enforceVerificationPolicy(sessionId, workspace, files, effectiveChecks);
            }
        } catch (RuntimeException e) {
            log.debug("记录校验证据失败：session={}, run={}", sessionId, runId, e);
        }
    }

    /**
     * 策略门禁在证据落账后执行：缺检查就把任务落到验证失败，而不是让“模型说完成”直接通过。
     * 策略文件不存在时保持原有行为；解析错误 fail-closed，避免静默放行。
     */
    private void enforceVerificationPolicy(String sessionId, String workspace, List<String> files,
        List<String> checks) {
        var policies = verificationPolicies;
        if (policies == null) {
            return;
        }
        try {
            var report = policies.evaluate(toolFactory.resolve(workspace), files, checks);
            if (report.configured() && !report.passed()) {
                taskControls.status(sessionId, TaskControlStatus.VERIFICATION_FAILED);
                log.info("验证策略未通过：session={}, missing={}", sessionId, report.missing());
            }
        } catch (RuntimeException e) {
            // 策略评估自身异常时 fail-closed：不能让异常把门禁变成放行。
            taskControls.status(sessionId, TaskControlStatus.VERIFICATION_FAILED);
            log.warn("验证策略评估失败，任务标记为验证失败：session={}", sessionId, e);
        }
    }

    private record PreparedRunContext(List<Message> history, TaskControlService.TaskContract executionTask,
        boolean unattended, PlanSink planSink, AgentRunPreparation.PreparedRun prepared, PermissionGate sessionGate,
        com.pilot.core.tool.ReadGuard readGuard, AtomicReference<AgentLoop> activeLoopRef,
        Consumer<AgentLoop> configurePreflight, List<Message> activatedHistory,
        Consumer<AgentLoop.Result> logPreflightTelemetry, java.util.function.Supplier<String> taskContextSource,
        RunUsageAccumulator runUsage, AtomicReference<RunCompleted> lastCompleted, Consumer<SessionEvent> sink) {
    }

    private record ExecutionRunContext(PreparedRunContext prepared, RunUsageAccumulator runUsage,
        AtomicReference<RunCompleted> lastCompleted, Consumer<SessionEvent> sink, ModelClient measuredMain,
        AgentLoop.Config config, GoalExecutionPolicy goalPolicy, ModelClient executionClient) {
    }

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

    private void publish(String sessionId, String runId, SessionEvent event) {
        try {
            sessions.publish(sessionId, runId, event);
        } catch (RuntimeException e) {
            log.warn("Dropped event {} for session {}", event.type().wireName(), sessionId, e);
        }
        var coordination = coordinationEvents;
        if (coordination != null) {
            coordination.sessionEvent(sessionId, runId, event);
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
