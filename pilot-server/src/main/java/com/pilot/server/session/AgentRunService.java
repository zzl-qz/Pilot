package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.event.PlanUpdated;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunStarted;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.core.tool.RunCommandTool;
import com.pilot.core.tool.CommandStatusTool;
import com.pilot.core.tool.CommandLogsTool;
import com.pilot.core.tool.StopCommandTool;
import com.pilot.core.workspace.PathValidator;
import tools.jackson.databind.ObjectMapper;
import com.pilot.core.trace.JsonlTraceRecorder;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.multi.DispatchAgentsTool;
import com.pilot.server.multi.MultiAgentService;
import com.pilot.server.workspace.RunSnapshotService;
import com.pilot.server.workspace.WorkspaceToolFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 在独立线程上执行 {@link AgentLoop}，把循环产生的事件写入会话。
 *
 * <p>对话记忆通过 {@code ConversationRepository} 落盘（默认 SQLite），每步结束都会 checkpoint，
 * 因此进程重启后可以用 {@code /continue} 接着跑；计划通过 {@code PlanRepository} 保存，
 * 并在每次运行时重新注入 system，保证压缩之后计划仍然可见。
 */
@Component
public class AgentRunService implements RunStarter {
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final int MAX_AUTO_CONTINUE = 3;
    /** 提示词正文在 {@code prompts/} 资源里，这里只做变量注入与拼接。 */
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();
    /** 项目约定文件，按优先级取第一个存在的。 */
    private static final List<String> CONVENTION_FILES = List.of("PILOT.md", "AGENTS.md", "CLAUDE.md");
    private static final int MAX_CONVENTION_CHARS = 8_000;
    /** 仓库快照：顶层条目与未提交改动都限行，避免 system 被大仓库撑爆。 */
    private static final int MAX_TREE_ENTRIES = 30;
    private static final int MAX_GIT_CHANGES = 12;
    private static final long GIT_TIMEOUT_MS = 2_000L;
    private static final Set<String> IGNORED_ENTRIES = Set.of(
            ".git", ".pilot", ".idea", ".vscode", ".gradle", ".venv", "__pycache__",
            "node_modules", "target", "build", "dist", "out");

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

    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
                           PermissionGate permissionGate, ApprovalBroker approvals,
                           AgentProperties properties, ModelSettings modelSettings,
                           RunSnapshotService snapshots, RunRegistry registry,
                           com.pilot.core.session.RunRepository runRepository,
                           ConversationRepository conversations,
                           com.pilot.core.session.PlanRepository plans, ObjectMapper objectMapper,
                           PilotDataDir dataDir, com.pilot.server.store.QueuedMessageRepository queuedMessages,
                           MultiAgentService multiAgent, MultiAgentProperties multiProperties,
                           com.pilot.server.observability.RunMetrics metrics, TaskControlService taskControls,
                           CommandTaskManager commandTasks) {
        this.sessions = sessions;
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
        launch(sessionId, runId, input, attachments == null ? List.of() : attachments, false, task);
    }

    @Override
    public boolean steer(String sessionId, String text) {
        return steer(sessionId, registry.activeRun(sessionId).orElse(null), text);
    }

    @Override
    public boolean steer(String sessionId, String expectedRunId, String text) {
        if (text == null || text.isBlank()) return false;
        synchronized (inbox(sessionId)) {
            String runId = registry.activeRun(sessionId).orElse(null);
            if (runId == null || !runId.equals(expectedRunId)) return false;
            RunHandle handle = runId == null ? null : runs.get(runId);
            if (handle == null || !handle.accepting || handle.cancelled.get()) return false;
            taskControls.correct(sessionId, text.strip());
            var pending = new ArrayList<>(queuedMessages.load("steer:" + sessionId));
            pending.add(text.strip());
            queuedMessages.save("steer:" + sessionId, pending);
            handle.pendingApprovals.forEach(callId -> approvals.resolve(callId, false));
            return true;
        }
    }

    @Override
    public void discardSteering(String sessionId) {
        synchronized (inbox(sessionId)) { queuedMessages.save("steer:" + sessionId, List.of()); }
    }

    private String pollSteering(String sessionId) {
        synchronized (inbox(sessionId)) {
            var pending = queuedMessages.load("steer:" + sessionId);
            if (pending.isEmpty()) return null;
            queuedMessages.save("steer:" + sessionId, List.of());
            return String.join("\n\n", pending);
        }
    }

    private boolean claimCompletion(String sessionId, RunHandle handle) {
        synchronized (inbox(sessionId)) {
            if (handle.cancelled.get() || !queuedMessages.load("steer:" + sessionId).isEmpty()) return false;
            handle.accepting = false;
            return true;
        }
    }

    @Override
    public boolean pause(String runId) {
        RunHandle handle = runs.get(runId);
        if (handle == null) return false;
        synchronized (inbox(handle.sessionId)) {
            if (!handle.accepting) return false;
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
    public boolean followup(String sessionId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            // 与运行结束后的队列交接共用锁，避免检查完活动运行后才入队，消息无人接走。
            if (registry.activeRun(sessionId).isEmpty()) {
                return false;
            }
            queue.offer(text.strip());
            persistQueue(sessionId, queue);
        }
        return true;
    }

    @Override
    public List<String> queued(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    @Override
    public boolean editQueued(String sessionId, int index, String text) {
        if (text == null || text.isBlank()) return false;
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            if (index < 0 || index >= queue.size()) {
                return false;
            }
            var items = new ArrayList<>(queue);
            items.set(index, text.strip());
            queue.clear();
            queue.addAll(items);
            persistQueue(sessionId, queue);
            return true;
        }
    }

    @Override
    public boolean removeQueued(String sessionId, int index) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            if (index < 0 || index >= queue.size()) {
                return false;
            }
            var items = new ArrayList<>(queue);
            items.remove(index);
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
        launch(sessionId, runId, input, attachments, continuation, null);
    }

    private void launch(String sessionId, String runId, String input, List<Attachment> attachments,
                        boolean continuation, TaskControlService.Requirements requirements) {
        launch(sessionId, runId, input, attachments, continuation, requirements, false);
    }

    private void launch(String sessionId, String runId, String input, List<Attachment> attachments,
                        boolean continuation, TaskControlService.Requirements requirements, boolean regenerate) {
        synchronized (inbox(sessionId)) {
            var handle = new RunHandle(sessionId);
            runs.put(runId, handle);
            try {
                registry.register(runId, sessionId, sessionWorkspace(sessionId));
                snapshots.saveConversation(runId, conversations.load(sessionId), plans.load(sessionId));
                taskControls.snapshot(sessionId, runId);
                var previous = taskControls.load(sessionId);
                if (!continuation && !regenerate) {
                    if (previous != null) plans.delete(sessionId);
                    taskControls.start(sessionId, input,
                            requirements == null ? List.of() : requirements.constraints(),
                            requirements == null ? List.of() : requirements.acceptance());
                    queuedMessages.save("steer:" + sessionId, List.of());
                } else if (previous != null) {
                    if (regenerate) {
                        plans.delete(sessionId);
                        taskControls.update(sessionId, previous.constraints(), previous.acceptance().stream()
                                .map(item -> new TaskControlService.Acceptance(item.text(), false, item.evidence())).toList());
                        queuedMessages.save("steer:" + sessionId, List.of());
                    }
                    taskControls.status(sessionId, "ACTIVE");
                } else {
                    String original = sessions.events(sessionId).stream()
                            .filter(event -> event.payload() instanceof RunStarted started
                                    && !started.input().equals("（继续上一次未完成的任务）"))
                            .map(event -> ((RunStarted) event.payload()).input()).reduce((first, last) -> last)
                            .orElse("继续对话中尚未完成的任务");
                    taskControls.start(sessionId, original, List.of(), List.of());
                }
                runRepository.started(runId, sessionId, java.time.Instant.now());
                handle.future = executor.submit(() -> runOnWorker(sessionId, runId, input, attachments, handle, continuation));
            } catch (RuntimeException e) {
                runs.remove(runId, handle);
                registry.unregister(runId);
                throw e;
            }
        }
    }

    /** 工作线程主体：无论怎么结束都要注销槽位，否则 /continue 会永久 409。 */
    private void runOnWorker(String sessionId, String runId, String input, List<Attachment> attachments,
                             RunHandle handle, boolean continuation) {
        long startedAt = System.currentTimeMillis();
        String status = "FAILED";
        boolean continued = false;
        try {
            status = execute(sessionId, runId, input, attachments, handle, continuation);
            continued = maybeAutoContinue(sessionId, status);
        } catch (RuntimeException e) {
            log.error("Run {} 收尾失败", runId, e);
            taskControls.status(sessionId, "FAILED");
            publish(sessionId, runId, new RunFailed("INTERNAL_ERROR", "执行失败：" + e.getMessage(), true));
        } finally {
            handle.accepting = false;
            runs.remove(runId, handle);
            try {
                runRepository.finished(runId, status, java.time.Instant.now());
            } catch (RuntimeException e) {
                log.warn("记录 run {} 结束状态失败", runId, e);
            }
            Deque<String> queue = inbox(sessionId);
            try {
                synchronized (queue) {
                    // 释放槽位和启动排队消息必须连在一起，HTTP 新请求不能抢进交接窗口。
                    registry.unregister(runId);
                    if (!continued && "COMPLETED".equals(status) && !handle.cancelled.get()
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

    @Override
    public java.util.Optional<String> claimRun(String sessionId) {
        synchronized (inbox(sessionId)) {
            String runId = "run_" + java.util.UUID.randomUUID();
            return registry.claim(runId, sessionId, sessionWorkspace(sessionId)) ? java.util.Optional.of(runId) : java.util.Optional.empty();
        }
    }

    private Path sessionWorkspace(String sessionId) {
        return toolFactory.resolve(sessions.find(sessionId).map(Session::workspace).orElse(null));
    }

    @Override
    public boolean lockWorkspace(String operationId, Path workspace) {
        if (commandTasks.hasRunningInWorkspace(workspace) || !registry.lockWorkspace(operationId, workspace)) return false;
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
    }

    /** 当前任务完整结束后，在队列锁内占位并启动下一条；失败、暂停、计划未完成时不消费。 */
    private void drainQueue(String sessionId, Deque<String> queue) {
        String next = queue.peek();
        if (next == null) {
            return;
        }
        String runId = claimRun(sessionId).orElse(null);
        if (runId == null) {
            return;
        }
        try {
            sessions.publish(sessionId, runId, new RunStarted(next));
            launch(sessionId, runId, next, List.of(), false);
        } catch (RuntimeException e) {
            registry.unregister(runId);
            publish(sessionId, runId, new RunFailed("QUEUE_START_FAILED", "排队消息启动失败，消息已保留", false));
            throw e;
        }
        queue.remove();
        persistQueue(sessionId, queue);
    }

    @Override
    public void requirementsUpdated(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            var task = taskControls.load(sessionId);
            if (task == null || !"COMPLETED".equals(task.status())
                    || registry.activeRun(sessionId).isPresent()
                    || plans.load(sessionId).stream().anyMatch(item -> !item.done())) return;
            try { drainQueue(sessionId, queue); }
            catch (RuntimeException e) { log.error("验收后的排队消息启动失败，队列已保留：{}", sessionId, e); }
        }
    }

    /**
     * 步数触顶时，如果计划里还有未完成项就自动接上下一轮。
     *
     * <p>两道刹车：最多连续 {@value #MAX_AUTO_CONTINUE} 次；计划状态连续两轮没有变化即视为卡住，停下等人工介入。
     */
    private boolean maybeAutoContinue(String sessionId, String status) {
        if (!"MAX_STEPS".equals(status)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        var items = plans.load(sessionId);
        if (items.stream().allMatch(com.pilot.core.plan.PlanItem::done)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }

        String signature = items.stream()
                .map(item -> item.step() + "=" + item.status())
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
        var task = taskControls.load(sessionId);
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
        launch(sessionId, runId, originalInput, List.of(), false, null, task != null);
    }

    @Override
    public boolean cancel(String runId) {
        RunHandle handle = runs.get(runId);
        if (handle == null) {
            return false;
        }
        handle.accepting = false;
        handle.cancelled.set(true);
        var future = handle.future;
        if (future != null) {
            future.cancel(true);
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
    private String systemPrompt(String sessionId, String workspace, Set<String> availableTools) {
        String prompt = PROMPTS.system(PromptLibrary.environment(workspace), availableTools);
        String rules = projectRules(workspace);
        if (!rules.isEmpty()) {
            prompt = prompt + "\n\n" + rules;
        }
        String today = PROMPTS.render("system/today",
                Map.of("today", java.time.LocalDate.now().toString())).strip();
        prompt = prompt + "\n\n" + today;
        String plan = planBrief(sessionId);
        return plan.isEmpty() ? prompt : prompt + "\n\n" + plan;
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
        names.add("spawn_agent");
        if (multiProperties.enabled()) {
            names.add("dispatch_agents");
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
            return PROMPTS.render("context/repository", Map.of(
                    "tree", workspaceTree(root),
                    "git", gitState(root),
                    "build", buildHint(root))).strip();
        } catch (RuntimeException e) {
            log.debug("生成仓库摘要失败", e);
            return "";
        }
    }

    private static String workspaceTree(Path root) {
        try (var entries = Files.list(root)) {
            List<String> names = entries
                    .map(path -> {
                        String name = path.getFileName().toString();
                        return Files.isDirectory(path) ? name + "/" : name;
                    })
                    .filter(name -> !IGNORED_ENTRIES.contains(name.endsWith("/")
                            ? name.substring(0, name.length() - 1)
                            : name))
                    .sorted()
                    .limit(MAX_TREE_ENTRIES + 1L)
                    .toList();
            if (names.isEmpty()) {
                return "（空目录）";
            }
            boolean truncated = names.size() > MAX_TREE_ENTRIES;
            String listed = String.join("\n", names.stream()
                    .limit(MAX_TREE_ENTRIES)
                    .map(name -> "- " + name)
                    .toList());
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
        String listed = lines.stream()
                .limit(MAX_GIT_CHANGES)
                .map(line -> "- " + line.strip())
                .collect(Collectors.joining("\n"));
        String more = lines.size() > MAX_GIT_CHANGES
                ? "\n- …（还有 " + (lines.size() - MAX_GIT_CHANGES) + " 个）"
                : "";
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
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return "Maven 项目：优先用工作区的 `mvnw`（Windows 是 `mvnw.cmd`），不要依赖系统 maven";
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return "Gradle 项目：用工作区的 `gradlew`";
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return "Node 项目：先看 package.json 的 scripts，再跑 `npm test` / `npm run build` 这类脚本";
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
        return "未识别到构建文件，改动后按项目实际情况验证";
    }

    /**
     * 把工作区里的项目约定注入 system：项目规范应该跟着仓库走，而不是写死在代码里。
     * 目前只读根目录的 {@code PILOT.md} / {@code AGENTS.md} / {@code CLAUDE.md}，取第一个存在的。
     */
    private String projectRules(String workspace) {
        Path root;
        try {
            root = toolFactory.resolve(workspace);
        } catch (RuntimeException e) {
            return "";
        }
        for (String name : CONVENTION_FILES) {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String rules = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (rules.isEmpty()) {
                    continue;
                }
                if (rules.length() > MAX_CONVENTION_CHARS) {
                    rules = rules.substring(0, MAX_CONVENTION_CHARS) + "\n…（约定文件过长，已截断）";
                }
                return PROMPTS.render("system/project-rules",
                        Map.of("source", name, "rules", rules)).strip();
            } catch (IOException e) {
                log.warn("读取项目约定文件失败：{}", file, e);
                return "";
            }
        }
        return "";
    }

    private String planBrief(String sessionId) {
        var items = plans.load(sessionId);
        if (items.isEmpty()) {
            return "";
        }
        var open = items.stream().filter(item -> !item.done()).toList();
        if (open.isEmpty()) {
            return PROMPTS.render("system/plan-done", Map.of()).strip();
        }
        String list = open.stream()
                .map(item -> "- [" + item.status() + "] " + item.step())
                .collect(Collectors.joining("\n"));
        return PROMPTS.render("system/plan-context", Map.of("planItems", list)).strip();
    }

    private String execute(String sessionId, String runId, String input, List<Attachment> attachments,
                           RunHandle handle, boolean continuation) {
        List<Message> history = conversations.load(sessionId);
        String workspace = sessions.find(sessionId).map(Session::workspace).orElse(null);
        PlanSink planSink = (items, explanation) -> {
            plans.save(sessionId, items);
            publish(sessionId, runId, new PlanUpdated(
                    items.stream()
                            .map(item -> new PlanUpdated.PlanItemPayload(item.step(), item.status()))
                            .toList(),
                    explanation));
        };
        ToolRegistry tools = sessionTools(sessionId, workspace, planSink);
        // 条件注册的 spawn_agent / dispatch_agents 位于 config 构造之后，先算出最终工具名，
        // 让 system prompt 只注入本轮真实可用的工具说明。
        Set<String> availableTools = sessionTools(tools);
        // 事件类型计数：压缩次数、工具调用量、失败码分布都从这里来
        Consumer<SessionEvent> sink = event -> {
            if (event instanceof com.pilot.core.event.ApprovalRequired approval) {
                handle.pendingApprovals.add(approval.callId());
                if (handle.cancelled.get() || !queuedMessages.load("steer:" + sessionId).isEmpty()) {
                    approvals.resolve(approval.callId(), false);
                }
            } else if (event instanceof com.pilot.core.event.ApprovalResolved approval) {
                handle.pendingApprovals.remove(approval.callId());
            }
            SessionEvent delivered = event;
            if (event instanceof com.pilot.core.event.RunCancelled) {
                taskControls.status(sessionId, handle.paused ? "PAUSED" : "INTERRUPTED");
                if (handle.paused) delivered = new RunFailed("PAUSED", "任务已暂停，目标、约束、验收条件和排队消息已保留。", true);
            } else if (event instanceof RunFailed) {
                taskControls.status(sessionId, "INTERRUPTED");
            } else if (event instanceof com.pilot.core.event.RunCompleted completed) {
                var task = completed.openSteps().isEmpty() ? taskControls.finish(sessionId)
                        : taskControls.status(sessionId, "INTERRUPTED");
                if (task != null && "AWAITING_REVIEW".equals(task.status())) {
                    var open = new ArrayList<>(completed.openSteps());
                    task.acceptance().stream().filter(item -> !item.checked())
                            .forEach(item -> open.add("待验收：" + item.text()));
                    delivered = new com.pilot.core.event.RunCompleted(completed.summary(), completed.changedFiles(),
                            completed.checks(), completed.planDone() + (int) task.acceptance().stream().filter(item -> item.checked()).count(),
                            completed.planTotal() + task.acceptance().size(), open);
                }
            }
            metrics.count("event." + delivered.type().wireName());
            publish(sessionId, runId, delivered);
        };
        Consumer<List<Message>> checkpoint = messages -> conversations.save(sessionId, messages);
        var config = new AgentLoop.Config(properties.maxSteps(), properties.repeatRemindAt(),
                properties.repeatAbortAt(),
                systemPrompt(sessionId, workspace, availableTools),
                // 收尾时读一次计划，把「本轮是否真的做完」一并写进 run.completed
                () -> plans.load(sessionId),
                // 计划没做完时自动继续，而不是停下来等用户点「继续执行」
                properties.maxPlanContinues(),
                properties.contextMaxChars(),
                properties.completionChecksEnabled());
        // 子代理与父代理共用工具集（其中不含 spawn_agent，避免递归派生）
        tools.register(new SpawnAgentTool(modelClient, tools, permissionGate, approvals, config,
                () -> conversations.load(sessionId), sink, handle.cancelled::get));
        // 多智能体调度：父 Agent 只能派活，子 Agent 的改动一律进沙箱
        if (multiProperties.enabled()) {
            tools.register(new DispatchAgentsTool(multiAgent, sessionId, runId, workspace, sink,
                    handle.cancelled::get));
        }
        // 与会话数据库、快照保持一致，统一落在数据目录（默认项目下的 .pilot）
        var traceRecorder = new JsonlTraceRecorder(
                dataDir.file("traces", sessionId + ".jsonl"), objectMapper);
        var loop = new AgentLoop(modelClient, tools, permissionGate, approvals, config, handle.cancelled::get,
                () -> pollSteering(sessionId), traceRecorder,
                delta -> sessions.preview(sessionId, runId, new AssistantPreview(delta)))
                .withTaskContext(() -> taskControls.prompt(sessionId) + commandContext(sessionId))
                .withCompletionClaim(() -> claimCompletion(sessionId, handle));

        snapshots.bind(runId, toolFactory.resolve(workspace));
        try {
            // 普通运行时，会话首条消息前注入一次仓库快照（见 withRepositoryBrief）；续跑不注入
            AgentLoop.Result result = continuation
                    ? loop.resume(history, sink, checkpoint)
                    : loop.run(input, attachments, withRepositoryBrief(history, workspace), sink, checkpoint);
            conversations.save(sessionId, result.conversation());
            log.info("Agent run {} completed: failureCode={}, messages={}, workspace={}", runId, result.failureCode(),
                    result.conversation().size(), workspace == null ? properties.workspace() : workspace);
            if (result.failureCode() == null) {
                var task = taskControls.load(sessionId);
                return task == null ? "COMPLETED" : task.status();
            }
            if (handle.paused) return "PAUSED";
            // 步数触顶要单独返回，外层才会按未完成计划自动接下一轮。
            // 以前一律收成 FAILED，自动继续的判断永远进不去。
            if ("CANCELLED".equals(result.failureCode()) || "MAX_STEPS".equals(result.failureCode())) {
                return result.failureCode();
            }
            return "FAILED";
        } catch (RuntimeException e) {
            log.error("Agent run {} failed unexpectedly", runId, e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            taskControls.status(sessionId, "FAILED");
            publish(sessionId, runId, new RunFailed("INTERNAL_ERROR", "执行失败：" + reason, false));
            return "FAILED";
        } finally {
            try {
                snapshots.finish();
            } finally {
                snapshots.clear();
            }
        }
    }

    private static final class RunHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Set<String> pendingApprovals = ConcurrentHashMap.newKeySet();
        private final String sessionId;
        private volatile boolean accepting = true;
        private volatile boolean paused;
        private volatile Future<?> future;
        private RunHandle(String sessionId) { this.sessionId = sessionId; }
    }

    /** 会话命令有独立的任务归属；默认和受限子代理工具集不共享这个入口。 */
    private ToolRegistry sessionTools(String sessionId, String workspace, PlanSink planSink) {
        var tools = new ToolRegistry();
        toolFactory.registryFor(workspace, planSink).tools().stream()
                .filter(tool -> !"run_command".equals(tool.name())).forEach(tools::register);
        tools.register(new RunCommandTool(new PathValidator(toolFactory.resolve(workspace)), commandTasks, sessionId));
        tools.register(new CommandStatusTool(commandTasks, sessionId));
        tools.register(new CommandLogsTool(commandTasks, sessionId));
        tools.register(new StopCommandTool(commandTasks, sessionId));
        return tools;
    }

    private String commandContext(String sessionId) {
        var tasks = commandTasks.list(sessionId);
        if (tasks.isEmpty()) return "";
        return "\n\n本会话最近的命令任务（后台命令独立运行，已启动不代表验证通过）：\n"
                + tasks.stream().limit(10).map(task -> "- " + task.taskId() + " [" + task.status() + "] "
                        + com.pilot.core.tool.ToolSupport.oneLine(task.command(), 180)
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
            log.warn("模型 API Key 未配置，模型请求会失败；"
                    + "可在界面「模型设置」里填写，或设置环境变量 PILOT_MODEL_API_KEY");
        } else {
            log.info("模型已配置：model={} endpoint={} workspace={}",
                    snapshot.model(), snapshot.endpoint(), properties.workspace());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
