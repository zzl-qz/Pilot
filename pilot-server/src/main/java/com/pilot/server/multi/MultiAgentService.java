package com.pilot.server.multi;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.PlanUpdated;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.SandboxDiffReady;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.event.TaskUpdated;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.model.ModelClient;
import com.pilot.core.multi.AgentReport;
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
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.workspace.WorkspaceToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
    /** 依赖层内并行执行子任务。 */
    private final ExecutorService workers;
    /** 同任务多方案并行论证；与 workers 分开，避免嵌套提交互等。 */
    private final ExecutorService reviewers;

    public MultiAgentService(ModelClient modelClient, WorkspaceToolFactory toolFactory,
                             SandboxStore sandboxes, VerificationGate verification,
                             MultiAgentProperties properties, PilotDataDir dataDir,
                             ObjectMapper objectMapper) {
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

    @jakarta.annotation.PreDestroy
    void shutdown() {
        workers.shutdownNow();
        reviewers.shutdownNow();
    }

    /**
     * @param events    会话事件出口（父 run 的 sink）
     * @param cancelled 父 run 的取消信号，子 Agent 逐个检查
     */
    public record Dispatch(String sessionId, String runId, String workspace, List<TaskSpec> specs,
                           Consumer<SessionEvent> events, BooleanSupplier cancelled) {
    }

    /** 单个任务的最终结果。 */
    public record TaskOutcome(String taskId, String title, TaskStatus status, String agentId,
                              List<String> sandboxIds, AgentReport report, List<AgentReport> competing,
                              String detail) {
    }

    /**
     * @param completed   是否走完全部任务且通过硬校验（可以进入人工审批）
     * @param arbitrationId 需要人工仲裁时的编号；否则为空
     * @param report      交回父 Agent 的结构化文本
     */
    public record Result(boolean completed, List<TaskOutcome> outcomes, MergePrecheck.Report merge,
                         VerificationGate.Report verification, List<DissentReport> dissents,
                         String arbitrationId, String report) {
    }

    public Result dispatch(Dispatch dispatch) {
        TaskGraph graph = new TaskGraph(dispatch.specs());
        Map<String, TaskState> states = new LinkedHashMap<>();
        for (TaskSpec spec : graph.specs()) {
            TaskState state = new TaskState(spec);
            states.put(spec.id(), state);
            publish(dispatch.events(), new TaskUpdated(spec.id(), "", spec.title(),
                    TaskStatus.PENDING.wireName(), "已加入调度计划"));
        }

        Set<String> finished = new HashSet<>();
        Set<String> started = new HashSet<>();
        boolean halted = false;
        boolean cancelled = false;

        while (!halted) {
            if (cancelled(dispatch)) {
                cancelled = true;
                break;
            }
            List<TaskSpec> ready = graph.ready(finished, started);
            if (ready.isEmpty()) {
                break;
            }
            List<Future<?>> futures = new ArrayList<>();
            for (TaskSpec spec : ready) {
                started.add(spec.id());
                TaskState state = states.get(spec.id());
                futures.add(workers.submit(() -> execute(spec, state, dispatch)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    halted = true;
                } catch (Exception e) {
                    log.warn("子任务执行异常", e);
                    halted = true;
                }
            }
            for (TaskSpec spec : ready) {
                TaskState state = states.get(spec.id());
                switch (state.status) {
                    case DONE -> finished.add(spec.id());
                    case FAILED, CANCELLED -> blockDownstream(graph, states, spec.id(), dispatch);
                    case DISSENT, CONFLICT -> halted = true;
                    default -> { }
                }
            }
        }

        for (TaskState state : states.values()) {
            if (state.status == TaskStatus.PENDING) {
                update(state, cancelled ? TaskStatus.CANCELLED : TaskStatus.BLOCKED,
                        cancelled ? "父运行已取消" : "等待人工仲裁或上游阻塞", dispatch);
            }
        }

        List<DiffSandbox> boxes = collectedSandboxes(states);
        MergePrecheck.Report merge = MergePrecheck.check(boxes);
        List<DissentReport> dissents = states.values().stream()
                .filter(state -> state.dissent != null)
                .map(state -> state.dissent)
                .toList();

        String arbitrationId = "";
        // 分歧优先于文本冲突：多方案场景下互相覆盖几乎是必然的，先把方案差异交给用户更重要
        if (!dissents.isEmpty()) {
            arbitrationId = "arb_" + randomSuffix();
            DissentReport first = dissents.get(0);
            publish(dispatch.events(), new ArbitrationRequired(arbitrationId, "dissent", first.taskId(),
                    DissentReport.options(first.sides().size()), first.toText()));
        } else if (merge.hasConflict()) {
            markConflicts(merge, states, dispatch);
            arbitrationId = "arb_" + randomSuffix();
            publish(dispatch.events(), new ArbitrationRequired(arbitrationId, "conflict", "",
                    List.of("调整任务顺序重新生成", "放弃其中一份变更", "人工修改后重跑"),
                    "## 🔴 变更冲突\n\n" + merge.describe()
                            + "\n\n> 父 Agent 不会自动合并冲突文件，请决定调整顺序、重新生成或放弃某份变更。"));
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
                publish(dispatch.events(), new ArbitrationRequired(arbitrationId, "verification", "",
                        List.of("让子 Agent 重新生成改动", "人工修复后重跑校验", "放弃本次变更"),
                        "## ❌ 硬校验未通过\n\n" + verificationReport.detail()
                                + "\n\n已执行：" + String.join("；", verificationReport.checks())));
            } else {
                // 只有「每个任务都完成 + 硬校验没有失败」才算整批通过
                completed = states.values().stream()
                        .allMatch(state -> state.status == TaskStatus.DONE);
            }
        }

        List<TaskOutcome> outcomes = states.values().stream().map(TaskState::outcome).toList();
        recordBoard(dispatch, states);
        String report = buildReport(outcomes, merge, verificationReport, dissents, completed);
        return new Result(completed, outcomes, merge, verificationReport, dissents, arbitrationId, report);
    }

    private void execute(TaskSpec spec, TaskState state, Dispatch dispatch) {
        if (cancelled(dispatch)) {
            update(state, TaskStatus.CANCELLED, "父运行已取消", dispatch);
            return;
        }
        update(state, TaskStatus.RUNNING, "子 Agent 已启动", dispatch);
        int count = spec.reviewers();
        if (count <= 1) {
            Attempt attempt = runWithRetry(spec, dispatch);
            applySingle(spec, state, attempt, dispatch);
            return;
        }
        List<CompletableFuture<Attempt>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> runWithRetry(spec, dispatch), reviewers));
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
        } else {
            update(state, TaskStatus.FAILED, attempt.failure(), dispatch);
        }
    }

    /** 沙箱进仓库并登记到任务上；空沙箱（纯调研任务）不登记，免得看板堆一堆空目录。 */
    private void collect(Dispatch dispatch, TaskState state, DiffSandbox sandbox) {
        if (sandbox == null || sandbox.isEmpty()) {
            return;
        }
        sandboxes.add(dispatch.sessionId(), sandbox);
        state.track(sandbox);
    }

    /** 多方案：结论不一致就停下等人工仲裁，绝不自动投票。 */
    private void applyParallel(TaskSpec spec, TaskState state, List<Attempt> attempts, Dispatch dispatch) {
        List<Attempt> ok = attempts.stream().filter(Attempt::ok).toList();
        state.agentId = ok.isEmpty() ? "" : ok.get(0).agentId();
        if (ok.isEmpty()) {
            update(state, TaskStatus.FAILED, "所有方案都未完成：" + attempts.stream()
                    .map(Attempt::failure).reduce((a, b) -> a + "；" + b).orElse("未知原因"), dispatch);
            return;
        }
        List<AgentReport> reports = ok.stream().map(Attempt::report).toList();
        if (DissentReport.differs(reports)) {
            state.competing.addAll(reports);
            state.dissent = new DissentReport(spec.id(), spec.title(), reports,
                    "- 各方案都给出了可核验证据，但结论不一致，需要你判断哪一方的依据更符合预期。\n"
                            + "- 父 Agent 不会替你挑选：请选择方案、要求重新论证，或废弃该任务。");
            ok.forEach(attempt -> collect(dispatch, state, attempt.sandbox()));
            update(state, TaskStatus.DISSENT,
                    "方案结论不一致（" + reports.size() + " 个方案），等待人工仲裁", dispatch);
            return;
        }
        // 结论一致：只保留第一份改动，其余方案作为佐证存档，避免同文件被重复修改
        state.report = reports.get(0);
        collect(dispatch, state, ok.get(0).sandbox());
        update(state, TaskStatus.DONE, summary(reports.get(0)), dispatch);
    }

    private Attempt runWithRetry(TaskSpec spec, Dispatch dispatch) {
        Attempt last = null;
        int attempts = properties.maxAttempts();
        for (int round = 1; round <= attempts; round++) {
            if (cancelled(dispatch)) {
                return last != null ? last : Attempt.cancelled();
            }
            String note = round == 1 ? "" : "这是第 " + round + " 次尝试，上一次未完成："
                    + (last == null ? "未知原因" : last.failure()) + "。请换一种做法，不要重复同样的调用。";
            last = runOnce(spec, dispatch, note);
            if (last.ok()) {
                return last;
            }
            log.info("子任务 {} 第 {} 次尝试失败：{}", spec.id(), round, last.failure());
        }
        return last == null ? Attempt.cancelled() : last;
    }

    private Attempt runOnce(TaskSpec spec, Dispatch dispatch, String note) {
        String agentId = "agent_" + randomSuffix();
        String sandboxId = "sbx_" + randomSuffix();
        DiffSandbox sandbox = new DiffSandbox(sandboxId, agentId, spec.id(), "");
        SandboxSink sink = (path, oldText, newText) -> {
            SandboxChange change = sandbox.add(path, oldText, newText, oldText != null, spec.risk());
            if (change != null) {
                publish(dispatch.events(), new SandboxDiffReady(sandboxId, agentId, spec.id(), change.path(),
                        change.added(), change.removed()));
            }
        };
        ToolRegistry tools = toolFactory.registryForScope(dispatch.workspace(), spec.scope(), sink);
        AgentLoop.Config config = new AgentLoop.Config(properties.maxSteps(), 3,
                systemPrompt(spec, dispatch), List::of, 0, 120_000);
        // 每个子 Agent 单独一份 trace：出问题能按 agentId 回放它自己的工具调用链
        var trace = new JsonlTraceRecorder(
                dataDir.file("traces", dispatch.sessionId() + "-" + agentId + ".jsonl"), objectMapper);
        var loop = new AgentLoop(modelClient, tools, new PolicyPermissionGate(PermissionMode.AUTO),
                ApprovalBroker.denyAll(), config, () -> cancelled(dispatch), () -> null, trace);

        publish(dispatch.events(), new SubagentStarted(agentId, oneLine(spec.title() + "：" + spec.goal(), 200)));
        try {
            AgentLoop.Result result = loop.run(userPrompt(spec, note), List.of(),
                    subagentSink(dispatch.events()));
            if (result.failed()) {
                String reason = "子 Agent 未完成：" + result.failureCode();
                publish(dispatch.events(), new SubagentFinished(agentId, reason, false));
                return new Attempt(agentId, List.of(sandboxId), sandbox, null, false, reason);
            }
            AgentReport report = AgentReport.parse(agentId, spec.id(), result.finalText());
            publish(dispatch.events(), new SubagentFinished(agentId, report.headline(), true));
            return new Attempt(agentId, List.of(sandboxId), sandbox, report, true, "");
        } catch (RuntimeException e) {
            String reason = "子 Agent 异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            publish(dispatch.events(), new SubagentFinished(agentId, reason, false));
            return new Attempt(agentId, List.of(sandboxId), sandbox, null, false, reason);
        }
    }

    /**
     * 子 Agent 的事件只放行工具与子代理事件。
     *
     * <p>模型增量、run.completed / run.failed 属于父会话语义，直接放行会把子 Agent 的文字混进父回答、
     * 甚至把整个会话标成失败。
     */
    private static Consumer<SessionEvent> subagentSink(Consumer<SessionEvent> sink) {
        return event -> {
            if (event instanceof AssistantDelta || event instanceof AssistantPreview
                    || event instanceof ContextCompacted || event instanceof PlanUpdated
                    || event instanceof RunCompleted || event instanceof RunFailed
                    || event instanceof RunCancelled || event instanceof RunContinued
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
            var name = toolFactory.resolve(dispatch.workspace()).getFileName();
            if (name != null) {
                workspace = name.toString();
            }
        } catch (RuntimeException ignored) {
            // 工作区名只用于提示词，取不到就退化成通用描述
        }
        return PROMPTS.render("subagent/base", Map.of(
                "taskId", spec.id(),
                "workspace", workspace,
                "scope", spec.scope().describe(),
                "risk", spec.risk().label())).strip();
    }

    private static String userPrompt(TaskSpec spec, String note) {
        return PROMPTS.render("subagent/job", Map.of(
                "title", spec.title(),
                "goal", spec.goal(),
                "note", note == null || note.isBlank() ? "（首次尝试）" : note)).strip();
    }

    private void blockDownstream(TaskGraph graph, Map<String, TaskState> states, String taskId,
                                 Dispatch dispatch) {
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
            views.add(new SandboxStore.TaskView(state.spec.id(), state.spec.title(), state.agentId,
                    state.status.wireName(), state.detail, state.sandboxRefs.stream()
                    .mapToInt(DiffSandbox::fileCount).sum()));
        }
        sandboxes.recordTasks(dispatch.sessionId(), views);
    }

    private String buildReport(List<TaskOutcome> outcomes, MergePrecheck.Report merge,
                               VerificationGate.Report verification, List<DissentReport> dissents,
                               boolean completed) {
        StringBuilder out = new StringBuilder();
        out.append("## 多智能体调度结果：").append(completed ? "全部任务通过" : "需要人工处理").append('\n');
        out.append("### 任务状态\n");
        for (TaskOutcome outcome : outcomes) {
            out.append("- ").append(outcome.taskId()).append(' ').append(outcome.status().label())
                    .append("（").append(outcome.title()).append('）');
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
                out.append("- ").append(dissent.title()).append("：")
                        .append(dissent.sides().size()).append(" 个方案结论不一致，已发起人工仲裁。\n");
            }
        }
        out.append("\n### 你现在要做的\n");
        if (completed) {
            out.append("- 改动都在沙箱里，等用户在变更面板接受后才会落地；向用户说明每个任务的结论与校验结果。\n");
        } else {
            out.append("- 立即停止推进，把上面的报告交给用户，等待用户选择；不要自行决定采用哪个方案，也不要继续派发下游任务。\n");
        }
        String text = out.toString();
        return text.length() <= MAX_REPORT_CHARS
                ? text
                : text.substring(0, MAX_REPORT_CHARS) + "\n…（报告过长已截断）";
    }

    private void update(TaskState state, TaskStatus next, String detail, Dispatch dispatch) {
        state.status = next;
        state.detail = detail == null ? "" : detail;
        publish(dispatch.events(), new TaskUpdated(state.spec.id(), state.agentId, state.spec.title(),
                next.wireName(), state.detail));
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
    private record Attempt(String agentId, List<String> sandboxIds, DiffSandbox sandbox,
                           AgentReport report, boolean ok, String failure) {
        static Attempt cancelled() {
            return new Attempt("", List.of(), null, null, false, "父运行已取消");
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
            return new TaskOutcome(spec.id(), spec.title(), status, agentId, List.copyOf(sandboxIds),
                    report, List.copyOf(competing), detail);
        }
    }
}
