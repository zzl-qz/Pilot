package com.pilot.server.multi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.MultiAgentBudgetUpdated;
import com.pilot.core.event.PlanUpdated;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SandboxDiffReady;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.event.TaskUpdated;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Cancellation;
import com.pilot.core.loop.ContextCompactor;
import com.pilot.core.loop.ExecutionPolicy;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelRequestScope;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.multi.AgentReport;
import com.pilot.core.multi.AgentScope;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.DissentReport;
import com.pilot.core.multi.MergePrecheck;
import com.pilot.core.multi.SandboxChange;
import com.pilot.core.multi.TaskGraph;
import com.pilot.core.multi.TaskSpec;
import com.pilot.core.multi.TaskStatus;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.trace.JsonlTraceRecorder;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentBudget;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PermissionSettings;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.SpawnAgentTool;
import com.pilot.server.session.SpawnAgentTool.UsageSink;
import com.pilot.server.store.JdbcDispatchStore;
import com.pilot.server.workspace.WorkspaceToolFactory;

import tools.jackson.databind.ObjectMapper;

/**
 * 父调度 Agent 的执行内核：按 DAG 拉子 Agent、收沙箱改动、做合并预检与硬校验。
 *
 * <p>这里刻意不做「选优」：冲突与分歧一律收集成报告交给人工，父 Agent 只负责梳理与推进依赖。
 * 所有子 Agent 共享同一份只读工作区视图，写入只进各自沙箱。
 */
@Component
public class MultiAgentService {
    private static final Logger log = LoggerFactory.getLogger(MultiAgentService.class);
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();
    private static final int MAX_REPORT_CHARS = 8_000;
    private static final int MAX_CONCLUSION_CHARS = 400;

    private final ModelClient modelClient;
    private final WorkspaceToolFactory toolFactory;
    private final SandboxStore sandboxes;
    private final VerificationGate verification;
    private final MultiAgentProperties properties;
    private final PilotDataDir dataDir;
    private final ObjectMapper objectMapper;
    /** 子 Agent 只能继承父会话档位并收紧，不能自己放开权限。 */
    private final PermissionSettings permissions;
    private final AgentProperties agent;
    /** 依赖层内并行执行子任务。 */
    private final ExecutorService workers;
    /** 同任务多方案并行论证；与 workers 分开，避免嵌套提交互等。 */
    private final ExecutorService reviewers;
    /** 派发图与尝试记录的持久化出口；未配置存储时为空，调度行为不变。 */
    private final JdbcDispatchStore dispatchStore;
    /** 预算上限；默认 30 分钟 / token 不限，由 {@code pilot.multi.budget.*} 覆盖。 */
    private volatile MultiAgentBudget budgetLimits = MultiAgentBudget.DEFAULT;

    public MultiAgentService(ModelClient modelClient, WorkspaceToolFactory toolFactory, SandboxStore sandboxes,
        VerificationGate verification, MultiAgentProperties properties, PilotDataDir dataDir, ObjectMapper objectMapper,
        PermissionSettings permissions, AgentProperties agent) {
        this(modelClient, toolFactory, sandboxes, verification, properties, dataDir, objectMapper, permissions, agent,
            null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MultiAgentService(ModelClient modelClient, WorkspaceToolFactory toolFactory, SandboxStore sandboxes,
        VerificationGate verification, MultiAgentProperties properties, PilotDataDir dataDir, ObjectMapper objectMapper,
        PermissionSettings permissions, AgentProperties agent, JdbcDispatchStore dispatchStore) {
        this.dispatchStore = dispatchStore;
        this.permissions = permissions;
        this.agent = agent;
        this.modelClient = modelClient;
        this.toolFactory = toolFactory;
        this.sandboxes = sandboxes;
        this.verification = verification;
        this.properties = properties;
        this.dataDir = dataDir;
        this.objectMapper = objectMapper;
        this.workers = Executors.newFixedThreadPool(properties.maxParallel(), runnable -> {
            Thread thread = new Thread(runnable, "pilot-multi-task");
            thread.setDaemon(true);
            return thread;
        });
        this.reviewers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "pilot-multi-review");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 预算上限注入；没有配置时保持默认值，测试可直接覆盖。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBudgetLimits(MultiAgentBudget limits) {
        this.budgetLimits = limits == null ? MultiAgentBudget.DEFAULT : limits;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        workers.shutdownNow();
        reviewers.shutdownNow();
    }

    /**
     * @param events    会话事件出口（父 run 的 sink）
     * @param cancelled 父 run 的取消信号，子 Agent 逐个检查
     * @param usageSink 子 Agent 模型用量归集：计入父任务事故熔断与 run.usage 聚合；空时用 no-op
     */
    public record Dispatch(String sessionId, String runId, String workspace, List<TaskSpec> specs,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, ModelClient executionClient, UsageSink usageSink,
        String dispatchId, PermissionMode parentPermission) {
        public Dispatch(String sessionId, String runId, String workspace, List<TaskSpec> specs,
            Consumer<SessionEvent> events, BooleanSupplier cancelled, ModelClient executionClient, UsageSink usageSink,
            String dispatchId) {
            this(sessionId, runId, workspace, specs, events, cancelled, executionClient, usageSink, dispatchId, null);
        }

        /** 父 run 的显式授权快照；模型参数不能设置该字段。 */
        public Dispatch withParentPermission(PermissionMode permission) {
            return new Dispatch(sessionId, runId, workspace, specs, events, cancelled, executionClient, usageSink,
                dispatchId, permission);
        }

        public Dispatch(String sessionId, String runId, String workspace, List<TaskSpec> specs,
            Consumer<SessionEvent> events, BooleanSupplier cancelled) {
            this(sessionId, runId, workspace, specs, events, cancelled, null, null, "");
        }

        public Dispatch(String sessionId, String runId, String workspace, List<TaskSpec> specs,
            Consumer<SessionEvent> events, BooleanSupplier cancelled, ModelClient executionClient) {
            this(sessionId, runId, workspace, specs, events, cancelled, executionClient, null, "");
        }

        public Dispatch(String sessionId, String runId, String workspace, List<TaskSpec> specs,
            Consumer<SessionEvent> events, BooleanSupplier cancelled, ModelClient executionClient,
            UsageSink usageSink) {
            this(sessionId, runId, workspace, specs, events, cancelled, executionClient, usageSink, "");
        }

        /** 绑定持久化编号：调度开始时才生成，之前为空表示「还没落库」。 */
        public Dispatch withDispatchId(String id) {
            return new Dispatch(sessionId, runId, workspace, specs, events, cancelled, executionClient, usageSink,
                id == null ? "" : id, parentPermission);
        }

        UsageSink usageSinkOrNull() {
            return usageSink == null ? SpawnAgentTool.UsageSink.none() : usageSink;
        }
    }

    /** 单个任务的最终结果。 */
    public record TaskOutcome(String taskId, String title, TaskStatus status, String agentId, List<String> sandboxIds,
        AgentReport report, List<AgentReport> competing, String detail) {
    }

    /**
     * @param completed   是否走完全部任务且通过硬校验（可以进入人工审批）
     * @param arbitrationId 需要人工仲裁时的编号；否则为空
     * @param report      交回父 Agent 的结构化文本
     */
    public record Result(boolean completed, List<TaskOutcome> outcomes, MergePrecheck.Report merge,
        VerificationGate.Report verification, List<DissentReport> dissents, String arbitrationId, String report,
        String dispatchId) {
    }

    public Result dispatch(Dispatch dispatch) {
        TaskGraph graph = new TaskGraph(dispatch.specs());
        // 先落库再推进：记录图与节点摘要，重启后可查中断位置；完整权限快照与续调度另行实现。
        String dispatchId = "dsp_" + randomSuffix();
        dispatch = dispatch.withDispatchId(dispatchId);
        if (dispatchStore != null) {
            dispatchStore.saveDispatch(dispatchId, dispatch.sessionId(), dispatch.runId(), dispatch.workspace(),
                "running");
            dispatchStore.saveNodes(dispatchId, graph.specs());
        }
        final Dispatch scoped = dispatch;
        MultiAgentBudgetLedger budget =
            new MultiAgentBudgetLedger(budgetLimits, snapshot -> publishBudget(scoped, snapshot));
        publishBudget(scoped, budget.snapshot());
        try {
            return dispatchWithBudget(scoped, graph, dispatchId, budget);
        } finally {
            budget.close();
        }
    }

    private Result dispatchWithBudget(Dispatch dispatch, TaskGraph graph, String dispatchId,
        MultiAgentBudgetLedger budget) {
        final Dispatch scoped = dispatch;
        Map<String, TaskState> states = new LinkedHashMap<>();
        for (TaskSpec spec : graph.specs()) {
            TaskState state = new TaskState(spec);
            states.put(spec.id(), state);
            publish(dispatch.events(),
                new TaskUpdated(spec.id(), "", spec.title(), TaskStatus.PENDING.wireName(), "已加入调度计划"));
        }

        recordBoard(dispatch, states);
        Set<String> finished = new HashSet<>();
        Set<String> started = new HashSet<>();
        boolean halted = false;
        boolean cancelled = false;

        while (!halted) {
            if (MultiAgentService.cancelled(dispatch)) {
                cancelled = true;
                break;
            }
            List<TaskSpec> ready = graph.ready(finished, started);
            if (ready.isEmpty()) {
                break;
            }
            // 预算超了就整批停住：不再开新的子 Agent，已完成的产物保留，报告里写清原因
            String overrun = budget.reason();
            if (!overrun.isEmpty()) {
                for (TaskSpec spec : ready) {
                    TaskState state = states.get(spec.id());
                    if (state.status == TaskStatus.PENDING) {
                        update(state, TaskStatus.BLOCKED, overrun, dispatch);
                    }
                }
                halted = true;
                break;
            }
            List<Future<?>> futures = new ArrayList<>();
            for (TaskSpec spec : ready) {
                started.add(spec.id());
                TaskState state = states.get(spec.id());
                futures.add(workers.submit(() -> execute(spec, state, scoped, states, budget)));
            }
            for (Future<?> future : futures) {
                try {
                    long remaining = budget.remainingNanos();
                    if (remaining <= 0) {
                        budget.expireNow();
                        throw new TimeoutException("派发预算墙钟时间已耗尽");
                    }
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    halted = true;
                    futures.forEach(active -> active.cancel(true));
                    break;
                } catch (TimeoutException e) {
                    budget.expireNow();
                    futures.forEach(active -> active.cancel(true));
                    halted = true;
                } catch (CancellationException e) {
                    halted = true;
                } catch (Exception e) {
                    log.warn("子任务执行异常", e);
                    halted = true;
                }
            }
            for (TaskSpec spec : ready) {
                TaskState state = states.get(spec.id());
                if (state.status == TaskStatus.RUNNING && (cancelled || !budget.reason().isEmpty())) {
                    update(state, cancelled ? TaskStatus.CANCELLED : TaskStatus.BLOCKED,
                        cancelled ? "父运行已取消；已请求中断子任务" : budget.reason(), dispatch);
                }
                switch (state.status) {
                    case DONE -> finished.add(spec.id());
                    case FAILED, CANCELLED -> blockDownstream(graph, states, spec.id(), dispatch);
                    case DISSENT, CONFLICT -> halted = true;
                    default -> {
                    }
                }
            }
        }

        String budgetStop = budget.reason();
        for (TaskState state : states.values()) {
            if (state.status == TaskStatus.PENDING) {
                update(state, cancelled ? TaskStatus.CANCELLED : TaskStatus.BLOCKED,
                    cancelled ? "父运行已取消" : budgetStop.isEmpty() ? "等待人工仲裁或上游阻塞" : budgetStop, dispatch);
            }
        }

        List<DiffSandbox> boxes = collectedSandboxes(states);
        MergePrecheck.Report merge = MergePrecheck.check(boxes);
        List<DissentReport> dissents =
            states.values().stream().filter(state -> state.dissent != null).map(state -> state.dissent).toList();

        String arbitrationId = "";
        // 分歧优先于文本冲突：多方案场景下互相覆盖几乎是必然的，先把方案差异交给用户更重要
        if (!dissents.isEmpty()) {
            arbitrationId = "arb_" + randomSuffix();
            DissentReport first = dissents.get(0);
            raiseArbitration(dispatch, new ArbitrationRequired(arbitrationId, "dissent", first.taskId(),
                DissentReport.options(first.sides().size()), first.toText()));
        } else if (merge.hasConflict()) {
            markConflicts(merge, states, dispatch);
            arbitrationId = "arb_" + randomSuffix();
            raiseArbitration(dispatch,
                new ArbitrationRequired(arbitrationId, "conflict", "", List.of("调整任务顺序重新生成", "放弃其中一份变更", "人工修改后重跑"),
                    "## 🔴 变更冲突\n\n" + merge.describe() + "\n\n> 父 Agent 不会自动合并冲突文件，请决定调整顺序、重新生成或放弃某份变更。"));
        }

        VerificationGate.Report verificationReport = null;
        boolean completed = false;
        if (arbitrationId.isEmpty()) {
            List<SandboxChange> merged = MergePrecheck.mergedChanges(boxes);
            if (merged.isEmpty()) {
                verificationReport = VerificationGate.Report.skipped("没有文件改动，不需要硬校验");
            } else {
                verificationReport = verification.verify(toolFactory.resolve(dispatch.workspace()), "", merged);
            }
            if (verificationReport.status().equals(VerificationGate.Report.FAILED)) {
                arbitrationId = "arb_" + randomSuffix();
                raiseArbitration(dispatch, new ArbitrationRequired(arbitrationId, "verification", "",
                    List.of("让子 Agent 重新生成改动", "人工修复后重跑校验", "放弃本次变更"), "## ❌ 硬校验未通过\n\n" + verificationReport.detail()
                        + "\n\n已执行：" + String.join("；", verificationReport.checks())));
            } else {
                // 只有「每个任务都完成 + 硬校验没有失败」才算整批通过
                completed = states.values().stream().allMatch(state -> state.status == TaskStatus.DONE)
                    && (merged.isEmpty() || verificationReport.ok());
            }
            // 校验结论绑定到每个沙箱当时的产物清单：候选或依赖一变，旧的通过记录自动失效
            for (DiffSandbox box : boxes) {
                sandboxes.recordVerification(dispatch.sessionId(), box.id(), SandboxStore.environmentFingerprint(),
                    verificationReport);
            }
        }

        List<TaskOutcome> outcomes = states.values().stream().map(TaskState::outcome).toList();
        recordBoard(dispatch, states);
        if (cancelled) {
            budget.stopForCancellation();
        } else {
            budget.complete();
        }
        String report = buildReport(outcomes, merge, verificationReport, dissents, completed, budget.snapshot());
        if (dispatchStore != null) {
            dispatchStore.saveDispatch(dispatchId, dispatch.sessionId(), dispatch.runId(), dispatch.workspace(),
                completed ? "awaiting_acceptance" : arbitrationId.isEmpty() ? "failed" : "awaiting_decision");
        }
        return new Result(completed, outcomes, merge, verificationReport, dissents, arbitrationId, report, dispatchId);
    }

    private void execute(TaskSpec spec, TaskState state, Dispatch dispatch, Map<String, TaskState> states,
        MultiAgentBudgetLedger budget) {
        if (MultiAgentService.cancelled(dispatch)) {
            update(state, TaskStatus.CANCELLED, "父运行已取消", dispatch);
            return;
        }
        // 预算先于执行：超了就不再开新的子 Agent，保留已有产物等用户决定
        String overrun = budget.reason();
        if (!overrun.isEmpty()) {
            update(state, TaskStatus.BLOCKED, overrun, dispatch);
            return;
        }
        update(state, TaskStatus.RUNNING, "子 Agent 已启动", dispatch);
        int count = spec.reviewers();
        if (count <= 1) {
            Attempt attempt = runWithRetry(spec, dispatch, states, budget);
            applySingle(spec, state, attempt, dispatch);
            return;
        }
        List<CompletableFuture<Attempt>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> runWithRetry(spec, dispatch, states, budget), reviewers));
        }
        List<Attempt> attempts = new ArrayList<>();
        for (CompletableFuture<Attempt> future : futures) {
            try {
                attempts.add(future.join());
            } catch (RuntimeException e) {
                log.warn("并行方案执行失败", e);
            }
        }
        applyParallel(spec, state, attempts, dispatch);
    }

    private void applySingle(TaskSpec spec, TaskState state, Attempt attempt, Dispatch dispatch) {
        state.agentId = attempt.agentId();
        state.report = attempt.report();
        if (attempt.ok()) {
            collect(dispatch, state, attempt.sandbox());
            update(state, TaskStatus.DONE, summary(attempt.report()), dispatch);
        } else if (attempt.budgetStopReason() != null && !attempt.budgetStopReason().isBlank()) {
            collect(dispatch, state, attempt.sandbox());
            update(state, TaskStatus.BLOCKED, attempt.budgetStopReason(), dispatch);
        } else {
            update(state, TaskStatus.FAILED, attempt.failure(), dispatch);
        }
    }

    /** 沙箱进仓库并登记到任务上；空沙箱（纯调研任务）不登记，免得看板堆一堆空目录。 */
    private void raiseArbitration(Dispatch dispatch, ArbitrationRequired event) {
        sandboxes.raise(dispatch.sessionId(), new SandboxStore.Arbitration(event.arbitrationId(), event.kind(),
            event.taskId(), event.options(), event.report()));
        publish(dispatch.events(), event);
    }

    private void collect(Dispatch dispatch, TaskState state, DiffSandbox sandbox) {
        if (sandbox == null || sandbox.isEmpty()) {
            return;
        }
        sandboxes.add(dispatch.sessionId(), sandbox);
        // 登记「这个沙箱依赖哪些上游任务的产物」，上游变化时下游校验才会失效
        sandboxes.linkTaskDependencies(dispatch.sessionId(), state.spec.dependsOn(), sandbox.id());
        state.track(sandbox);
    }

    /** 多方案：结论不一致就停下等人工仲裁，绝不自动投票。 */
    private void applyParallel(TaskSpec spec, TaskState state, List<Attempt> attempts, Dispatch dispatch) {
        List<Attempt> ok = attempts.stream().filter(Attempt::ok).toList();
        state.agentId = ok.isEmpty() ? "" : ok.get(0).agentId();
        if (ok.isEmpty()) {
            List<Attempt> budgetStopped = attempts.stream()
                .filter(attempt -> attempt.budgetStopReason() != null && !attempt.budgetStopReason().isBlank())
                .toList();
            if (!budgetStopped.isEmpty()) {
                budgetStopped.forEach(attempt -> collect(dispatch, state, attempt.sandbox()));
                String reason = budgetStopped.stream().map(Attempt::budgetStopReason).distinct()
                    .reduce((left, right) -> left + "；" + right).orElse("派发预算软停止");
                update(state, TaskStatus.BLOCKED, reason, dispatch);
                return;
            }
            update(state, TaskStatus.FAILED,
                "所有方案都未完成：" + attempts.stream().map(Attempt::failure).reduce((a, b) -> a + "；" + b).orElse("未知原因"),
                dispatch);
            return;
        }
        List<AgentReport> reports = ok.stream().map(Attempt::report).toList();
        if (DissentReport.differs(reports)) {
            state.competing.addAll(reports);
            state.dissent = new DissentReport(spec.id(), spec.title(), reports,
                "- 各方案都给出了可核验证据，但结论不一致，需要你判断哪一方的依据更符合预期。\n" + "- 父 Agent 不会替你挑选：请选择方案、要求重新论证，或废弃该任务。");
            ok.forEach(attempt -> collect(dispatch, state, attempt.sandbox()));
            update(state, TaskStatus.DISSENT, "方案结论不一致（" + reports.size() + " 个方案），等待人工仲裁", dispatch);
            return;
        }
        // 结论一致：只保留第一份改动，其余方案作为佐证存档，避免同文件被重复修改
        state.report = reports.get(0);
        collect(dispatch, state, ok.get(0).sandbox());
        update(state, TaskStatus.DONE, summary(reports.get(0)), dispatch);
    }

    private Attempt runWithRetry(TaskSpec spec, Dispatch dispatch, Map<String, TaskState> states,
        MultiAgentBudgetLedger budget) {
        try (MultiAgentBudgetLedger.WorkerLease ignored = budget.registerWorker()) {
            Attempt last = null;
            String lane = "lane_" + randomSuffix();
            int attempts = properties.maxAttempts();
            for (int round = 1; round <= attempts; round++) {
                String overrun = budget.reason();
                if (!overrun.isEmpty()) {
                    return last != null ? last
                        : Attempt.budgetStopped("", List.of(), null, overrun, JdbcDispatchStore.Usage.EMPTY);
                }
                if (MultiAgentService.cancelled(dispatch)) {
                    return last != null ? last : Attempt.cancelled();
                }
                String note = round == 1 ? ""
                    : "这是第 " + round + " 次尝试，上一次未完成：" + (last == null ? "未知原因" : last.failure()) + "。请换一种做法，不要重复同样的调用。";
                TaskState tracked = states.get(spec.id());
                if (tracked != null) {
                    tracked.attempts = round;
                }
                last = runOnce(spec, dispatch, note, states, lane, budget);
                if (last.ok()) {
                    return last;
                }
                if (last.budgetStopReason() != null) {
                    return last;
                }
                log.info("子任务 {} 第 {} 次尝试失败：{}", spec.id(), round, last.failure());
            }
            return last == null ? Attempt.cancelled() : last;
        }
    }

    private Attempt runOnce(TaskSpec spec, Dispatch dispatch, String note, Map<String, TaskState> states, String lane,
        MultiAgentBudgetLedger budget) {
        String agentId = "agent_" + randomSuffix();
        String sandboxId = "sbx_" + randomSuffix();
        DiffSandbox sandbox = new DiffSandbox(sandboxId, agentId, spec.id(), "");
        // 本任务自己的改动出口
        SandboxSink own = new SandboxSink() {
            @Override
            public Map<String, String> proposedFiles() {
                Map<String, String> files = new LinkedHashMap<>();
                sandbox.changes().forEach(change -> files.put(change.path(), change.newContent()));
                return files;
            }

            @Override
            public void record(String path, String oldText, String newText) {
                SandboxChange change = sandbox.add(path, oldText, newText, oldText != null, spec.risk());
                if (change != null) {
                    sandboxes.checkpoint(dispatch.sessionId(), sandbox);
                    publish(dispatch.events(), new SandboxDiffReady(sandboxId, agentId, spec.id(), change.path(),
                        change.added(), change.removed()));
                }
            }

            @Override
            public String currentText(String path, String baselineText) {
                return sandbox.currentText(path, baselineText);
            }

            @Override
            public boolean proposed(String path) {
                return sandbox.hasChange(path);
            }

            @Override
            public String proposedText(String path) {
                return sandbox.proposedText(path);
            }
        };
        // 读取时叠加上游产物：下游读文件看到的是上游改过的内容，而不是磁盘上的旧版本
        SandboxSink sink = own.overlay(upstreamView(spec, states));
        PermissionMode mode = effectiveMode(spec.scope(), dispatch.parentPermission());
        if (mode == PermissionMode.READ_ONLY && spec.scope() != null && spec.scope().writable()) {
            // 不能让子 Agent 偷偷放开权限完成可写任务：明确告诉用户要切档位
            return new Attempt(agentId, List.of(), sandbox, null, false,
                "会话权限档位为 READ_ONLY，无法派发需要写入或命令的子任务；请先在界面切换到「完全放行」", JdbcDispatchStore.Usage.EMPTY);
        }
        ToolRegistry tools = toolFactory.registryForScope(dispatch.workspace(), spec.scope(), sink);
        // 上下文预算与收尾门跟随主会话配置，避免子 Agent 用更小的窗口和额外的收尾门做不完任务
        AgentLoop.Config config = new AgentLoop.Config(agent.maxSteps(), 3, 5, systemPrompt(spec, dispatch), List::of,
            0, agent.contextMaxChars(), agent.completionChecksEnabled());
        // 每个子 Agent 单独一份 trace：出问题能按 agentId 回放它自己的工具调用链
        JsonlTraceRecorder trace = new JsonlTraceRecorder(
            dataDir.file("traces", dispatch.sessionId() + "-" + agentId + ".jsonl"), objectMapper);
        UsageMeter meter = new UsageMeter();
        ModelClient selected = dispatch.executionClient() == null ? modelClient : dispatch.executionClient();
        ModelClient metered = usageCounting(selected, dispatch.usageSinkOrNull());
        ModelClient budgeted = usageMetering(metered, meter, budget);
        ThreadLocal<String> cancellationReason = new ThreadLocal<>();
        Cancellation cancellation = new Cancellation() {
            @Override
            public boolean cancelled() {
                if (MultiAgentService.cancelled(dispatch)) {
                    cancellationReason.set("CANCELLED");
                    return true;
                }
                if (budget.shouldCancelCurrentWorker()) {
                    cancellationReason.set("BUDGET_EXHAUSTED:" + budget.reason());
                    return true;
                }
                cancellationReason.remove();
                return false;
            }

            @Override
            public boolean cancelledBeforeTool() {
                if (MultiAgentService.cancelled(dispatch)) {
                    cancellationReason.set("CANCELLED");
                    return true;
                }
                if (budget.shouldCancelBeforeTool()) {
                    cancellationReason.set("BUDGET_EXHAUSTED:" + budget.reason());
                    return true;
                }
                cancellationReason.remove();
                return false;
            }

            @Override
            public String reason() {
                return cancellationReason.get();
            }
        };
        AgentLoop loop = new AgentLoop(budgeted, tools, new PolicyPermissionGate(mode), ApprovalBroker.denyAll(),
            config, cancellation, () -> null, trace);

        publish(dispatch.events(), new SubagentStarted(agentId, oneLine(spec.title() + "：" + spec.goal(), 200),
            oneLine(spec.id() + " " + spec.title(), 40)));
        // 每次尝试都拿一份新租约：重试时代次递增，旧执行者的晚到结果写不进来
        JdbcDispatchStore.Lease lease =
            dispatchStore == null || dispatch.dispatchId().isBlank() ? JdbcDispatchStore.Lease.NONE
                : dispatchStore.startAttempt(dispatch.dispatchId(), spec.id(), agentId, sandboxId, lane);
        try {
            AgentLoop.Result result = loop.run(userPrompt(spec, note, dependencyContext(spec, states)), List.of(),
                subagentSink(dispatch.events(), agentId));
            if (result.failed()) {
                String budgetReason = budget.reason();
                if (!cancelled(dispatch)
                    && ("BUDGET_EXHAUSTED".equals(result.failureCode()) || !budgetReason.isEmpty())) {
                    return budgetStopped(dispatch, lease, agentId, sandboxId, sandbox, meter.snapshot(), budgetReason);
                }
                String reason = "子 Agent 未完成：" + result.failureCode();
                finish(lease, "failed", reason, meter.snapshot());
                publish(dispatch.events(), new SubagentFinished(agentId, reason, false));
                return new Attempt(agentId, List.of(sandboxId), sandbox, null, false, reason, meter.snapshot());
            }
            AgentReport report = AgentReport.parse(agentId, spec.id(), result.finalText());
            if (!finish(lease, "succeeded", "", meter.snapshot())) {
                sandboxes.discardCheckpoint(dispatch.sessionId(), sandboxId);
                String reason = "执行租约已失效，已丢弃晚到产物";
                publish(dispatch.events(), new SubagentFinished(agentId, reason, false));
                return new Attempt(agentId, List.of(), null, null, false, reason, meter.snapshot());
            }
            publish(dispatch.events(), new SubagentFinished(agentId, report.headline(), true));
            return new Attempt(agentId, List.of(sandboxId), sandbox, report, true, "", meter.snapshot());
        } catch (RuntimeException e) {
            String budgetReason = budget.reason();
            if (!cancelled(dispatch) && !budgetReason.isEmpty()) {
                return budgetStopped(dispatch, lease, agentId, sandboxId, sandbox, meter.snapshot(), budgetReason);
            }
            String reason = "子 Agent 异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            finish(lease, "failed", reason, meter.snapshot());
            publish(dispatch.events(), new SubagentFinished(agentId, reason, false));
            return new Attempt(agentId, List.of(sandboxId), sandbox, null, false, reason, meter.snapshot());
        }
    }

    private Attempt budgetStopped(Dispatch dispatch, JdbcDispatchStore.Lease lease, String agentId, String sandboxId,
        DiffSandbox sandbox, JdbcDispatchStore.Usage usage, String reason) {
        String detail = "派发预算软停止：" + (reason == null || reason.isBlank() ? "预算已用尽" : reason);
        finish(lease, "budget_stopped", detail, usage);
        publish(dispatch.events(), new SubagentFinished(agentId, detail, false));
        return Attempt.budgetStopped(agentId, List.of(sandboxId), sandbox, detail, usage);
    }

    /** 结算尝试；代次过期（被新尝试取代）时记一条日志，绝不让旧执行者改写状态。 */
    private boolean finish(JdbcDispatchStore.Lease lease, String status, String failure,
        JdbcDispatchStore.Usage usage) {
        if (dispatchStore == null || lease == null || lease.attemptId().isEmpty()) {
            return true;
        }
        if (!dispatchStore.finishAttempt(lease.attemptId(), lease.generation(), status, failure, usage)) {
            log.warn("尝试 {} 的回执已过期（代次 {}），结果被丢弃", lease.attemptId(), lease.generation());
            return false;
        }
        return true;
    }

    /**
     * 子 Agent 的有效权限：继承父会话档位，只能收紧不能放开（与 Codex 子 Agent 一致）。
     * 只读任务永远拿不到写权限；父会话档位为 null 时按最保守的 READ_ONLY 处理。
     */
    private PermissionMode effectiveMode(AgentScope scope, PermissionMode authorizedParent) {
        PermissionMode parent =
            authorizedParent != null ? authorizedParent : permissions == null ? null : permissions.mode();
        if (scope != null && !scope.writable()) {
            return PermissionMode.READ_ONLY;
        }
        return parent == null ? PermissionMode.READ_ONLY : parent;
    }

    /** Admits every model request before dispatch and settles usage immediately after its response. */
    private static ModelClient usageMetering(ModelClient delegate, UsageMeter meter, MultiAgentBudgetLedger budget) {
        return new ModelClient() {
            @Override
            public ModelProfile profile() {
                return delegate.profile();
            }

            @Override
            public Set<NativeCapability> nativeCapabilities() {
                return delegate.nativeCapabilities();
            }

            @Override
            public boolean disableRejectedNativeTools(Throwable failure) {
                return delegate.disableRejectedNativeTools(failure);
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                return chat(messages, tools, null);
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta) {
                return chat(messages, tools, textDelta, null);
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta,
                Consumer<String> reasoningDelta) {
                long estimatedInput =
                    ContextCompactor.estimateTokens(messages) + ContextCompactor.estimateTokens(String.valueOf(tools));
                MultiAgentBudgetLedger.RequestPermit permit = budget.beginRequest(estimatedInput);
                if (permit == null) {
                    throw new ExecutionPolicy.Stop("BUDGET_EXHAUSTED", budget.reason());
                }

                ModelResponse response = null;
                RuntimeException failure = null;
                try {
                    response =
                        ModelRequestScope.within(ModelRequestScope.current().purpose().isBlank() ? "subagent" : null,
                            null, false, () -> delegate.chat(messages, tools, textDelta, reasoningDelta));
                } catch (RuntimeException e) {
                    failure = e;
                }

                ModelUsage reported = response == null ? null : response.usage();
                long input = reported != null && reported.inputTokens() > 0 ? reported.inputTokens() : estimatedInput;
                long output = reported != null && reported.present() ? reported.outputTokens() : response == null ? 0
                    : ContextCompactor.estimateTokens(response.content() + String.valueOf(response.toolCalls()));
                boolean exact = reported != null && reported.exact() && reported.inputTokens() > 0;
                long total = input > Long.MAX_VALUE - output ? Long.MAX_VALUE : input + output;
                meter.record(new ModelUsage(input, output, 0, exact));
                MultiAgentBudgetLedger.Settlement settlement =
                    permit.finish(total, exact ? 0 : total, response != null);

                if (failure != null) {
                    String stopReason = budget.reason();
                    if (!stopReason.isEmpty()) {
                        Thread.interrupted();
                        throw new ExecutionPolicy.Stop("BUDGET_EXHAUSTED", stopReason);
                    }
                    throw failure;
                }
                if (settlement.stoppedBeforeSettle()) {
                    Thread.interrupted();
                    throw new ExecutionPolicy.Stop("BUDGET_EXHAUSTED", settlement.stopReason());
                }
                return response;
            }
        };
    }

    /**
     * 把子 Agent 的每次模型请求计入父任务的 usage 归集（与 spawn_agent 同模式）：
     * 事故熔断拿请求数，run.usage 聚合拿 token 明细。包装只记账不干预响应。
     */
    private static ModelClient usageCounting(ModelClient delegate, UsageSink sink) {
        return new ModelClient() {
            @Override
            public ModelProfile profile() {
                return delegate.profile();
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                return chat(messages, tools, null);
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta) {
                return chat(messages, tools, textDelta, null);
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta,
                Consumer<String> reasoningDelta) {
                sink.beforeRequest();
                long started = System.nanoTime();
                ModelResponse response = null;
                try {
                    response =
                        ModelRequestScope.within(ModelRequestScope.current().purpose().isBlank() ? "subagent" : null,
                            null, false, () -> delegate.chat(messages, tools, textDelta, reasoningDelta));
                    return response;
                } finally {
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    sink.afterRequest(elapsed, response == null ? ModelUsage.unknown() : response.usage());
                }
            }
        };
    }

    /**
     * 子 Agent 的事件只放行工具与子代理事件。
     *
     * <p>模型增量、run.completed / run.failed 属于父会话语义，直接放行会把子 Agent 的文字混进父回答、
     * 甚至把整个会话标成失败。
     */
    private static Consumer<SessionEvent> subagentSink(Consumer<SessionEvent> sink, String agentId) {
        return event -> {
            if (event instanceof ToolStarted started) {
                // 多个子 Agent 并行时必须带归属，前端才分得清哪条操作是谁发起的。
                sink.accept(started.forAgent(agentId));
                return;
            }
            if (event instanceof AssistantDelta || event instanceof AssistantPreview
                || event instanceof AssistantReasoning || event instanceof ContextCompacted
                || event instanceof PlanUpdated || event instanceof RunUsageUpdated || event instanceof RunCompleted
                || event instanceof RunFailed || event instanceof RunCancelled || event instanceof RunContinued
                || event instanceof RunRetrying || event instanceof ApprovalRequired
                || event instanceof ApprovalResolved) {
                return;
            }
            sink.accept(event);
        };
    }

    private String systemPrompt(TaskSpec spec, Dispatch dispatch) {
        String workspace = "当前工作区";
        try {
            Path name = toolFactory.resolve(dispatch.workspace()).getFileName();
            if (name != null) {
                workspace = name.toString();
            }
        } catch (RuntimeException ignored) {
            // 工作区名只用于提示词，取不到就退化成通用描述
        }
        // 只读子代理拿极简版：没有「改动怎么提交」段，减少无效指令（dsh 极简模式的同款思路）
        String template = spec.scope().writable() ? "subagent/base" : "subagent/base-readonly";
        return PROMPTS.render(template, Map.of("taskId", spec.id(), "workspace", workspace, "scope",
            spec.scope().describe(), "risk", spec.risk().label())).strip();
    }

    /**
     * 上游任务的产物视图（只读）：把依赖链上所有已完成的沙箱叠成一个读取层。
     *
     * <p>只做读取叠加，不做合并写回：下游改自己的文件，上游产物对它可见但不可改。
     * 上游没有产物时返回 {@code null}，此时读取直接落到真实工作区。
     */
    private SandboxSink upstreamView(TaskSpec spec, Map<String, TaskState> states) {
        List<DiffSandbox> boxes = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        collectUpstream(spec.id(), states, visited, boxes);
        if (boxes.isEmpty()) {
            return null;
        }
        return new SandboxSink() {
            @Override
            public Map<String, String> proposedFiles() {
                Map<String, String> files = new LinkedHashMap<>();
                for (DiffSandbox box : boxes) {
                    box.changes().forEach(change -> files.put(change.path(), change.newContent()));
                }
                return files;
            }

            @Override
            public void record(String path, String oldText, String newText) {
                throw new IllegalStateException("上游产物只读，不能在这里写入: " + path);
            }

            @Override
            public String currentText(String path, String baselineText) {
                String text = baselineText;
                for (DiffSandbox box : boxes) {
                    String proposed = box.proposedText(path);
                    if (proposed != null) {
                        text = proposed;
                    }
                }
                return text;
            }

            @Override
            public boolean proposed(String path) {
                for (DiffSandbox box : boxes) {
                    if (box.hasChange(path)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String proposedText(String path) {
                String text = null;
                for (DiffSandbox box : boxes) {
                    String proposed = box.proposedText(path);
                    if (proposed != null) {
                        text = proposed;
                    }
                }
                return text;
            }
        };
    }

    /** 沿依赖链收集上游沙箱：直接上游在前，越远的越靠前，下游取值时后者覆盖前者。 */
    private void collectUpstream(String taskId, Map<String, TaskState> states, Set<String> visited,
        List<DiffSandbox> boxes) {
        TaskState state = states.get(taskId);
        if (state == null || !visited.add(taskId)) {
            return;
        }
        for (String dependency : state.spec.dependsOn()) {
            if (visited.contains(dependency)) {
                continue;
            }
            collectUpstream(dependency, states, visited, boxes);
            TaskState upstream = states.get(dependency);
            if (upstream != null) {
                boxes.addAll(upstream.sandboxRefs);
            }
        }
    }

    private String dependencyContext(TaskSpec spec, Map<String, TaskState> states) {
        if (spec.dependsOn().isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder("上游依赖产物（只读输入）：\n");
        int chars = 0;
        for (String dependency : spec.dependsOn()) {
            TaskState state = states.get(dependency);
            if (state == null) {
                continue;
            }
            context.append("\n### ").append(dependency).append("：")
                .append(state.report == null ? state.detail : state.report.conclusion()).append('\n');
            for (DiffSandbox box : state.sandboxRefs) {
                for (SandboxChange change : box.changes()) {
                    String artifact = "\n#### 文件 " + change.path() + "\n```\n" + change.newContent() + "\n```\n";
                    if (chars + artifact.length() > 24000) {
                        context.append("\n（依赖产物达到 24,000 字符上限，后续内容省略）\n");
                        return context.toString();
                    }
                    context.append(artifact);
                    chars += artifact.length();
                }
            }
        }
        return context.toString();
    }

    private static String userPrompt(TaskSpec spec, String note, String dependencies) {
        return PROMPTS.render("subagent/job", Map.of("title", spec.title(), "goal", spec.goal(), "note",
            note == null || note.isBlank() ? "（首次尝试）" : note, "dependencies", dependencies == null ? "" : dependencies))
            .strip();
    }

    private void blockDownstream(TaskGraph graph, Map<String, TaskState> states, String taskId, Dispatch dispatch) {
        for (String downstream : graph.downstream(taskId)) {
            TaskState state = states.get(downstream);
            if (state != null && state.status == TaskStatus.PENDING) {
                update(state, TaskStatus.BLOCKED, "上游任务 " + taskId + " 失败，已阻断", dispatch);
            }
        }
    }

    private void markConflicts(MergePrecheck.Report merge, Map<String, TaskState> states, Dispatch dispatch) {
        Set<String> conflicted = new HashSet<>();
        for (MergePrecheck.Conflict conflict : merge.conflicts()) {
            for (String owner : conflict.owners()) {
                int at = owner.indexOf('@');
                String taskId = at < 0 ? owner : owner.substring(at + 1);
                conflicted.add(taskId);
            }
        }
        for (String taskId : conflicted) {
            TaskState state = states.get(taskId);
            if (state != null && state.status == TaskStatus.DONE) {
                update(state, TaskStatus.CONFLICT, "沙箱改动与其他任务重叠，等待人工裁决", dispatch);
            }
        }
    }

    private List<DiffSandbox> collectedSandboxes(Map<String, TaskState> states) {
        List<DiffSandbox> result = new ArrayList<>();
        for (TaskState state : states.values()) {
            result.addAll(state.sandboxRefs);
        }
        return result;
    }

    private void recordBoard(Dispatch dispatch, Map<String, TaskState> states) {
        List<SandboxStore.TaskView> views = new ArrayList<>();
        for (TaskState state : states.values()) {
            views.add(
                new SandboxStore.TaskView(state.spec.id(), state.spec.title(), state.agentId, state.status.wireName(),
                    state.detail, state.sandboxRefs.stream().mapToInt(DiffSandbox::fileCount).sum()));
        }
        sandboxes.recordTasks(dispatch.sessionId(), views);
    }

    private String buildReport(List<TaskOutcome> outcomes, MergePrecheck.Report merge,
        VerificationGate.Report verification, List<DissentReport> dissents, boolean completed,
        MultiAgentBudgetLedger.Snapshot budget) {
        StringBuilder out = new StringBuilder();
        out.append("## 多智能体调度结果：").append(completed ? "全部任务通过" : "需要人工处理").append('\n');
        out.append("### 任务状态\n");
        for (TaskOutcome outcome : outcomes) {
            out.append("- ").append(outcome.taskId()).append(' ').append(outcome.status().label()).append("（")
                .append(outcome.title()).append('）');
            if (!outcome.detail().isBlank()) {
                out.append("：").append(oneLine(outcome.detail(), 160));
            }
            if (!outcome.sandboxIds().isEmpty()) {
                out.append(" ｜ 沙箱 ").append(String.join(", ", outcome.sandboxIds()));
            }
            out.append('\n');
        }
        out.append("\n### 结论摘要\n");
        for (TaskOutcome outcome : outcomes) {
            if (outcome.report() != null) {
                out.append("- ").append(outcome.taskId()).append("：")
                    .append(oneLine(outcome.report().conclusion(), MAX_CONCLUSION_CHARS)).append('\n');
            }
            // 分歧任务没有「唯一结论」，把每个方案各列一行，避免父 Agent 只看到第一个
            for (AgentReport side : outcome.competing()) {
                out.append("- ").append(outcome.taskId()).append("（").append(side.agentId()).append("）：")
                    .append(oneLine(side.conclusion(), MAX_CONCLUSION_CHARS)).append('\n');
            }
        }
        out.append("\n### 合并预检\n").append(merge.describe()).append('\n');
        if (verification != null) {
            out.append("\n### 硬校验\n- 结果：").append(verification.status()).append('\n');
            for (String check : verification.checks()) {
                out.append("  - ").append(check).append('\n');
            }
            if (!verification.detail().isBlank()) {
                out.append("  - ").append(oneLine(verification.detail(), 600)).append('\n');
            }
        }
        if (!dissents.isEmpty()) {
            out.append("\n### 分歧\n");
            for (DissentReport dissent : dissents) {
                out.append("- ").append(dissent.title()).append("：").append(dissent.sides().size())
                    .append(" 个方案结论不一致，已发起人工仲裁。\n");
            }
        }
        out.append("\n### 派发预算\n");
        out.append("- 状态：").append(budget.status()).append('\n');
        out.append("- 墙钟上限：").append(budget.maxDispatchSeconds()).append(" 秒\n");
        out.append("- token 上限：").append(budget.maxTotalTokens() == 0 ? "不限" : budget.maxTotalTokens()).append('\n');
        out.append("- 已结算：").append(budget.usedTokens()).append(" token（估算 ").append(budget.estimatedTokens())
            .append("，其余按端点 usage 记账）\n");
        out.append("- 在途预留：").append(budget.reservedTokens()).append(" token 输入估算，").append(budget.inFlightRequests())
            .append(" 个请求尚未结算\n");
        if (!budget.stopReason().isBlank()) {
            out.append("- 停止原因：").append(oneLine(budget.stopReason(), 240)).append('\n');
        }
        if (budget.cancellationRequested()) {
            out.append("- 已向在途子任务请求中断；这不保证供应商撤销请求或停止计费。\n");
        }
        out.append("- 这是本地软预算；输出用量在响应返回后结算，在途请求可能导致超额。\n");
        out.append("\n### 你现在要做的\n");
        if (completed) {
            out.append("- 改动都在沙箱里，等用户在变更面板接受后才会落地；向用户说明每个任务的结论与校验结果。\n");
        } else {
            out.append("- 立即停止推进，把上面的报告交给用户，等待用户选择；不要自行决定采用哪个方案，也不要继续派发下游任务。\n");
        }
        String text = out.toString();
        return text.length() <= MAX_REPORT_CHARS ? text : text.substring(0, MAX_REPORT_CHARS) + "\n…（报告过长已截断）";
    }

    private void update(TaskState state, TaskStatus next, String detail, Dispatch dispatch) {
        state.status = next;
        state.detail = detail == null ? "" : detail;
        publish(dispatch.events(),
            new TaskUpdated(state.spec.id(), state.agentId, state.spec.title(), next.wireName(), state.detail));
        if (dispatchStore != null && !dispatch.dispatchId().isBlank()) {
            dispatchStore.updateNode(dispatch.dispatchId(), state.spec.id(), next.wireName(), state.agentId,
                state.detail, state.attempts);
        }
    }

    private static void publishBudget(Dispatch dispatch, MultiAgentBudgetLedger.Snapshot snapshot) {
        publish(dispatch.events(),
            new MultiAgentBudgetUpdated(dispatch.dispatchId(), snapshot.status(), snapshot.maxDispatchSeconds(),
                snapshot.maxTotalTokens(), snapshot.usedTokens(), snapshot.estimatedTokens(), snapshot.reservedTokens(),
                snapshot.inFlightRequests(), snapshot.stopReason(), snapshot.cancellationRequested()));
    }

    private static void publish(Consumer<SessionEvent> events, SessionEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException e) {
            log.debug("子任务事件推送失败", e);
        }
    }

    private static boolean cancelled(Dispatch dispatch) {
        return dispatch.cancelled() != null && dispatch.cancelled().getAsBoolean();
    }

    private static String summary(AgentReport report) {
        return report == null ? "" : report.headline();
    }

    private static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 一次子 Agent 实例的产出。 */
    private record Attempt(String agentId, List<String> sandboxIds, DiffSandbox sandbox, AgentReport report, boolean ok,
        String failure, JdbcDispatchStore.Usage usage, String budgetStopReason) {
        private Attempt(String agentId, List<String> sandboxIds, DiffSandbox sandbox, AgentReport report, boolean ok,
            String failure, JdbcDispatchStore.Usage usage) {
            this(agentId, sandboxIds, sandbox, report, ok, failure, usage, "");
        }

        static Attempt cancelled() {
            return new Attempt("", List.of(), null, null, false, "父运行已取消", JdbcDispatchStore.Usage.EMPTY, "");
        }

        static Attempt budgetStopped(String agentId, List<String> sandboxIds, DiffSandbox sandbox, String reason,
            JdbcDispatchStore.Usage usage) {
            return new Attempt(agentId, sandboxIds, sandbox, null, false, reason, usage, reason);
        }
    }

    /** 单次尝试的用量累计：per-agent 归集与预算判断都读它，不改写响应。 */
    private static final class UsageMeter {
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicLong requests = new AtomicLong();
        private volatile boolean exact = true;

        private void record(ModelUsage usage) {
            requests.incrementAndGet();
            if (usage == null) {
                exact = false;
                return;
            }
            input.addAndGet(usage.inputTokens());
            output.addAndGet(usage.outputTokens());
            exact = exact && usage.exact();
        }

        private JdbcDispatchStore.Usage snapshot() {
            return new JdbcDispatchStore.Usage(input.get(), output.get(), requests.get(), exact);
        }
    }

    private static final class TaskState {
        private final TaskSpec spec;
        private final List<String> sandboxIds = Collections.synchronizedList(new ArrayList<>());
        private final List<DiffSandbox> sandboxRefs = Collections.synchronizedList(new ArrayList<>());
        private final List<AgentReport> competing = Collections.synchronizedList(new ArrayList<>());
        private volatile TaskStatus status = TaskStatus.PENDING;
        private volatile String agentId = "";
        private volatile String detail = "";
        /** 已发生的尝试次数；持久化时一并写入，重启后能看出执行到第几次。 */
        private volatile int attempts = 0;
        private volatile AgentReport report;
        private volatile DissentReport dissent;

        private TaskState(TaskSpec spec) {
            this.spec = spec;
        }

        /** 记录沙箱引用，供合并预检与看板统计使用。 */
        private void track(DiffSandbox sandbox) {
            if (sandbox != null && sandboxRefs.stream().noneMatch(box -> box.id().equals(sandbox.id()))) {
                sandboxRefs.add(sandbox);
                sandboxIds.add(sandbox.id());
            }
        }

        private TaskOutcome outcome() {
            return new TaskOutcome(spec.id(), spec.title(), status, agentId, List.copyOf(sandboxIds), report,
                List.copyOf(competing), detail);
        }
    }
}
