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
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import tools.jackson.databind.ObjectMapper;
import com.pilot.core.trace.JsonlTraceRecorder;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.workspace.RunSnapshotService;
import com.pilot.server.workspace.WorkspaceToolFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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

    private final SessionService sessions;
    private final ModelClient modelClient;
    private final WorkspaceToolFactory toolFactory;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentProperties properties;
    private final ModelSettings modelSettings;
    private final RunSnapshotService snapshots;
    private final RunRegistry registry;
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

    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
                           PermissionGate permissionGate, ApprovalBroker approvals,
                           AgentProperties properties, ModelSettings modelSettings,
                           RunSnapshotService snapshots, RunRegistry registry,
                           com.pilot.core.session.RunRepository runRepository,
                           ConversationRepository conversations,
                           com.pilot.core.session.PlanRepository plans, ObjectMapper objectMapper,
                           PilotDataDir dataDir, com.pilot.server.store.QueuedMessageRepository queuedMessages) {
        this.sessions = sessions;
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
    public boolean steer(String sessionId, String text) {
        if (text == null || text.isBlank() || registry.activeRun(sessionId).isEmpty()) {
            return false;
        }
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
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

    /** 每轮采样前拉一条用户追加的指令；没有就返回 null。 */
    private String pollSteer(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            String next = queue.poll();
            if (next != null) persistQueue(sessionId, queue);
            return next;
        }
    }

    private void launch(String sessionId, String runId, String input, List<Attachment> attachments,
                        boolean continuation) {
        var handle = new RunHandle();
        runs.put(runId, handle);
        registry.register(runId, sessionId);
        runRepository.started(runId, sessionId, java.time.Instant.now());
        handle.future = executor.submit(() -> {
            String status = "FAILED";
            try {
                status = execute(sessionId, runId, input, attachments, handle, continuation);
            } finally {
                registry.unregister(runId);
                runRepository.finished(runId, status, java.time.Instant.now());
                runs.remove(runId, handle);
            }
            if (!maybeAutoContinue(sessionId, status) && !"CANCELLED".equals(status)) {
                drainQueue(sessionId);
            }
        });
    }

    /** 这轮结束时队列里还有话，就按顺序自动开下一轮，不让用户再点一次发送。 */
    private void drainQueue(String sessionId) {
        if (registry.activeRun(sessionId).isPresent()) {
            return;
        }
        String next = pollSteer(sessionId);
        if (next == null || next.isBlank()) {
            return;
        }
        String runId = "run_" + java.util.UUID.randomUUID();
        sessions.publish(sessionId, runId, new RunStarted(next));
        launch(sessionId, runId, next, List.of(), false);
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
        List<Message> history = conversations.load(sessionId);
        int lastUser = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                lastUser = i;
                break;
            }
        }
        if (lastUser >= 0) {
            conversations.save(sessionId, history.subList(0, lastUser));
        }
        start(sessionId, runId, input);
    }

    @Override
    public boolean cancel(String runId) {
        RunHandle handle = runs.get(runId);
        if (handle == null) {
            return false;
        }
        handle.cancelled.set(true);
        var future = handle.future;
        if (future != null) {
            future.cancel(true);
        }
        denyPendingApprovals();
        log.info("Run {} cancel requested", runId);
        return true;
    }

    /** 取消后不再有用户决策，挂起的审批直接按拒绝收尾，避免 Agent 线程继续等待。 */
    private void denyPendingApprovals() {
        for (var pending : approvals.pending()) {
            approvals.resolve(pending.callId(), false);
        }
    }

    /**
     * 把未完成的计划注入 system：压缩会折叠历史，但每轮重新注入的计划不会丢，
     * 这也是 Claude Code 要求把长期约束放 CLAUDE.md 的同款思路。
     */
    private String planBrief(String sessionId) {
        var items = plans.load(sessionId);
        if (items.isEmpty()) {
            return "";
        }
        var open = items.stream().filter(item -> !item.done()).toList();
        if (open.isEmpty()) {
            return "\n\n当前计划已全部完成；若用户提出新需求，重新 update_plan。";
        }
        var brief = new StringBuilder("\n\n当前任务计划（未完成项，按顺序推进）：");
        for (var item : open) {
            brief.append("\n- [").append(item.status()).append("] ").append(item.step());
        }
        return brief.toString();
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
        ToolRegistry tools = toolFactory.registryFor(workspace, planSink);
        Consumer<SessionEvent> sink = event -> publish(sessionId, runId, event);
        Consumer<List<Message>> checkpoint = messages -> conversations.save(sessionId, messages);
        var config = new AgentLoop.Config(properties.maxSteps(), properties.repeatedToolCallLimit(),
                AgentLoop.DEFAULT_SYSTEM_PROMPT + planBrief(sessionId),
                // 收尾时读一次计划，把「本轮是否真的做完」一并写进 run.completed
                () -> plans.load(sessionId),
                // 计划没做完时自动继续，而不是停下来等用户点「继续执行」
                properties.maxPlanContinues());
        // 子代理与父代理共用工具集（其中不含 spawn_agent，避免递归派生）
        tools.register(new SpawnAgentTool(modelClient, tools, permissionGate, approvals, config,
                () -> conversations.load(sessionId), sink, handle.cancelled::get));
        // 与会话数据库、快照保持一致，统一落在数据目录（默认项目下的 .pilot）
        var traceRecorder = new JsonlTraceRecorder(
                dataDir.file("traces", sessionId + ".jsonl"), objectMapper);
        var loop = new AgentLoop(modelClient, tools, permissionGate, approvals, config, handle.cancelled::get,
                () -> pollSteer(sessionId), traceRecorder,
                delta -> sessions.preview(sessionId, runId, new AssistantPreview(delta)));

        snapshots.bind(runId, toolFactory.resolve(workspace));
        try {
            AgentLoop.Result result = continuation
                    ? loop.resume(history, sink, checkpoint)
                    : loop.run(input, attachments, history, sink, checkpoint);
            conversations.save(sessionId, result.conversation());
            log.info("Agent run {} completed: failureCode={}, messages={}, workspace={}", runId, result.failureCode(),
                    result.conversation().size(), workspace == null ? properties.workspace() : workspace);
            if (result.failureCode() == null) {
                return "COMPLETED";
            }
            // 步数触顶要单独返回，外层才会按未完成计划自动接下一轮。
            // 以前一律收成 FAILED，自动继续的判断永远进不去。
            if ("CANCELLED".equals(result.failureCode()) || "MAX_STEPS".equals(result.failureCode())) {
                return result.failureCode();
            }
            return "FAILED";
        } catch (RuntimeException e) {
            log.error("Agent run {} failed unexpectedly", runId, e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            publish(sessionId, runId, new RunFailed("INTERNAL_ERROR", "执行失败：" + reason, false));
            return "FAILED";
        } finally {
            snapshots.clear();
        }
    }

    private static final class RunHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Future<?> future;
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
