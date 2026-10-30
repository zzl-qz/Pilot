package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunSteered;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.trace.TraceRecorder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 单 Agent 循环：请求模型 → 解析工具调用 → 执行工具 → 回填结果 → 继续，直到模型给出纯文本答复。
 *
 * <p>循环本身不关心会话：它只把协议事件交给 {@code events} 回调，
 * 由接入层补上 sessionId / runId 并写入 {@code SessionService}。
 *
 * <p>工具权限、审批挂起与执行细节在 {@link ToolExecutor}。
 * 本类有可变状态，一次运行请创建一个实例；运行应放到独立线程。
 */
public final class AgentLoop {
    /** 提示词模板的唯一入口；外部目录通过 {@code PILOT_PROMPTS_DIR} 覆盖。 */
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    /**
     * 默认系统提示词，正文在 {@code prompts/system/*.md}。生产路径由 {@code AgentRunService}
     * 用工作区名等信息重新渲染；这里保留给无会话场景与测试。
     */
    public static final String DEFAULT_SYSTEM_PROMPT =
            PROMPTS.system(PromptLibrary.environment(null));

    /**
     * @param repeatRemindAt       相同调用连续得到相同结果达到该次数时注入提醒；0 表示不提醒
     * @param repeatAbortAt         相同调用连续得到相同结果达到该次数时中断（含第一次）
     * @param plan                  当前计划快照，收尾时用来判断「任务是否真的做完」；默认无计划
     * @param maxPlanContinues      计划还有未完成项时自动催模型继续的次数上限；0 表示不自动继续
     * @param contextLimit          上下文预算（按字符估算）；太小会频繁压缩、丢细节
     */
    public record Config(int maxSteps, int repeatRemindAt, int repeatAbortAt, String systemPrompt,
                         Supplier<List<PlanItem>> plan, int maxPlanContinues, int contextLimit,
                         boolean completionChecks) {
        /** 兼容旧调用：连续无新结果达到 {@code repeatedToolCallLimit} 即中断，不提醒。 */
        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                      Supplier<List<PlanItem>> plan, int maxPlanContinues, int contextLimit,
                      boolean completionChecks) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, plan, maxPlanContinues,
                    contextLimit, completionChecks);
        }

        /** 兼容旧调用：收尾门默认开启。 */
        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                      Supplier<List<PlanItem>> plan, int maxPlanContinues, int contextLimit) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, plan, maxPlanContinues, contextLimit, true);
        }

        public Config {
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps must be >= 1");
            }
            if (repeatAbortAt < 1) {
                throw new IllegalArgumentException("repeatAbortAt must be >= 1");
            }
            if (repeatRemindAt < 0 || (repeatRemindAt > 0 && repeatRemindAt >= repeatAbortAt)) {
                throw new IllegalArgumentException(
                        "repeatRemindAt must be 0 (disabled) or less than repeatAbortAt");
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must not be blank");
            }
            if (maxPlanContinues < 0) {
                throw new IllegalArgumentException("maxPlanContinues must be >= 0");
            }
            if (contextLimit < 1_000) {
                throw new IllegalArgumentException("contextLimit must be >= 1000");
            }
            plan = plan == null ? List::of : plan;
        }

        /** 兼容旧调用：上下文预算用默认值，收尾门开启。 */
        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                      Supplier<List<PlanItem>> plan, int maxPlanContinues) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, plan, maxPlanContinues,
                    DEFAULT_CONTEXT_LIMIT, true);
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                      Supplier<List<PlanItem>> plan) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, plan, DEFAULT_MAX_PLAN_CONTINUES,
                    DEFAULT_CONTEXT_LIMIT, true);
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, List::of, DEFAULT_MAX_PLAN_CONTINUES,
                    DEFAULT_CONTEXT_LIMIT, true);
        }

        /** 渐进提醒：达到 {@code repeatRemindAt} 提醒，达到 {@code repeatAbortAt} 中断。 */
        public Config(int maxSteps, int repeatRemindAt, int repeatAbortAt, String systemPrompt) {
            this(maxSteps, repeatRemindAt, repeatAbortAt, systemPrompt, List::of,
                    DEFAULT_MAX_PLAN_CONTINUES, DEFAULT_CONTEXT_LIMIT, true);
        }

        /**
         * 默认步数只作为保险丝：真正约束模型的是上下文预算（{@code compact}）与
         * 重复工具调用检测，触顶后对话历史仍会保留，可以继续下一轮。
         */
        public static Config defaults() {
            return new Config(120, 0, 3, DEFAULT_SYSTEM_PROMPT, List::of, DEFAULT_MAX_PLAN_CONTINUES,
                    DEFAULT_CONTEXT_LIMIT, true);
        }
    }

    /**
     * @param finalText    成功时的最终文本
     * @param failureCode  失败码；成功为 {@code null}
     * @param conversation 本次运行后的对话历史（不含 system 消息），可用于同一会话的下一轮
     */
    public record Result(String finalText, String failureCode, List<Message> conversation) {
        public boolean failed() {
            return failureCode != null;
        }
    }

    private static final int DEFAULT_CONTEXT_LIMIT = 120_000;
    /** 动态后台状态即使暂时不变也可能稍后完成；给它比普通重复调用更宽的有界观察窗口。 */
    private static final int MIN_DYNAMIC_POLL_ABORT = 8;
    private static final int MAX_PARALLEL_READS = 8;
    /** 相同调用达到中断阈值后的缓冲轮数：先给模型强纠偏，仍无变化才中断。 */
    private static final int MAX_REPEAT_GRACE = 2;
    /** 历史里最多保留几条带图片的工具观察；更早的换成文字说明。 */
    private static final int MAX_TOOL_IMAGE_MESSAGES = 3;
    /** 计划未完成时默认自动催模型继续的次数上限。 */
    private static final int DEFAULT_MAX_PLAN_CONTINUES = 3;
    /** 等待重试时拆成小段，取消能在这段时间内停下来。 */
    private static final long RETRY_SLICE_MS = 250L;

    private final ModelClient client;
    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    /** 上下文预算的四层收缩策略与退避状态。 */
    private final ContextBudget budget;
    private final ToolExecutor toolExecutor;
    private final Config config;
    private final Cancellation cancellation;
    private final Supplier<String> pendingInput;
    private final TraceRecorder traceRecorder;
    private final Pause retryPause;
    /** 正在生成的文字，只给界面看。为空表示不推预览。 */
    private final Consumer<String> liveText;
    /** 收尾门：有了目标之后在 run/resume 里创建，一次运行一个。 */
    private CompletionGate completionGate;
    private ExecutionPolicy executionPolicy;

    public AgentLoop withExecutionPolicy(ExecutionPolicy policy) {
        executionPolicy = Objects.requireNonNull(policy);
        return this;
    }
    private Supplier<String> taskContext = () -> "";
    private BooleanSupplier completionClaim = () -> true;

    /** 每次模型请求重新注入持久化目标，避免压缩、续跑或纠正使约束丢失。 */
    public AgentLoop withTaskContext(Supplier<String> context) {
        taskContext = Objects.requireNonNull(context, "task context must not be null");
        return this;
    }

    /** 与消息入口共享的收尾占位：失败表示仍有新纠正或暂停需要处理。 */
    public AgentLoop withCompletionClaim(BooleanSupplier claim) {
        completionClaim = Objects.requireNonNull(claim, "completion claim must not be null");
        return this;
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate) {
        this(client, tools, permissionGate, ApprovalBroker.denyAll(), Config.defaults(), Cancellation.none());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, Config config) {
        this(client, tools, permissionGate, ApprovalBroker.denyAll(), config, Cancellation.none());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config) {
        this(client, tools, permissionGate, approvals, config, Cancellation.none());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config, Cancellation cancellation) {
        this(client, tools, permissionGate, approvals, config, cancellation, () -> null);
    }

    /**
     * @param pendingInput 在模型请求和工具执行之间拉取当前任务纠正；返回 null 表示没有
     */
    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config, Cancellation cancellation,
                     Supplier<String> pendingInput) {
        this(client, tools, permissionGate, approvals, config, cancellation, pendingInput,
                (call, result, latencyMs) -> { }, (Pause) Thread::sleep, null);
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config, Cancellation cancellation,
                     Supplier<String> pendingInput, TraceRecorder traceRecorder) {
        this(client, tools, permissionGate, approvals, config, cancellation, pendingInput,
                traceRecorder, Thread::sleep, null);
    }

    /**
     * @param liveText 模型正在吐的字。只更新界面，不进会话历史。
     */
    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config, Cancellation cancellation,
                     Supplier<String> pendingInput, TraceRecorder traceRecorder,
                     Consumer<String> liveText) {
        this(client, tools, permissionGate, approvals, config, cancellation, pendingInput,
                traceRecorder, Thread::sleep, liveText);
    }

    /**
     * @param retryPause 重试前的等待。生产环境睡觉，测试里记下来立刻返回。
     */
    AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
              ApprovalBroker approvals, Config config, Cancellation cancellation,
              Supplier<String> pendingInput, TraceRecorder traceRecorder, Pause retryPause,
              Consumer<String> liveText) {
        this.pendingInput = Objects.requireNonNull(pendingInput, "pendingInput must not be null");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
        this.retryPause = retryPause == null ? Thread::sleep : retryPause;
        this.liveText = liveText;
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.budget = new ContextBudget(new ContextCompactor(client)::compact);
        this.toolExecutor = new ToolExecutor(tools, this.permissionGate,
                Objects.requireNonNull(approvals, "approvals must not be null"), traceRecorder);
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
    }

    public Result run(String userInput, List<Message> history, Consumer<SessionEvent> events) {
        return run(userInput, history, events, messages -> { });
    }

    /**
     * @param checkpoint 每步结束后的记忆快照，用于把对话落盘（进程重启后仍可续跑）
     */
    public Result run(String userInput, List<Message> history, Consumer<SessionEvent> events,
                      Consumer<List<Message>> checkpoint) {
        return run(userInput, List.of(), history, events, checkpoint);
    }

    /**
     * @param attachments 随本条用户消息提交的附件（图片等），由模型客户端在发送时编码
     */
    public Result run(String userInput, List<Attachment> attachments, List<Message> history,
                      Consumer<SessionEvent> events, Consumer<List<Message>> checkpoint) {
        return run(userInput, attachments, List.of(), history, events, checkpoint);
    }

    public Result run(String userInput, List<Attachment> attachments, List<Integer> imagePositions,
                      List<Message> history, Consumer<SessionEvent> events,
                      Consumer<List<Message>> checkpoint) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be blank");
        }
        List<Message> messages = seed(history);
        String goal = userInput.strip();
        this.completionGate = new CompletionGate(PROMPTS, config.completionChecks(), goal);
        messages.add(Message.user(goal, attachments == null ? List.of() : attachments,
                imagePositions == null ? List.of() : imagePositions));
        return loop(messages, events, checkpoint);
    }

    /** 续跑：不追加用户消息，直接用已有记忆继续，服务「跑一半」之后的恢复。 */
    public Result resume(List<Message> history, Consumer<SessionEvent> events,
                         Consumer<List<Message>> checkpoint) {
        return resume(history, events, checkpoint, null);
    }

    /**
     * 带原始目标续跑。服务层从持久任务契约传入 goal，使只读调研的完成门在进程重启或
     * 用户显式继续后仍能按同一个目标核对覆盖范围。
     */
    public Result resume(List<Message> history, Consumer<SessionEvent> events,
                         Consumer<List<Message>> checkpoint, String goal) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        List<Message> messages = seed(history);
        if (messages.size() <= 1) {
            throw new IllegalArgumentException("history is empty, nothing to resume");
        }
        this.completionGate = new CompletionGate(PROMPTS, config.completionChecks(), goal);
        this.completionGate.restoreFrom(history);
        return loop(messages, events, checkpoint);
    }

    private List<Message> seed(List<Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(config.systemPrompt()));
        if (history != null) {
            messages.addAll(history);
        }
        return messages;
    }

    private Result loop(List<Message> messages, Consumer<SessionEvent> events,
                        Consumer<List<Message>> checkpoint) {
        String lastSignature = null;
        List<String> lastOutcomes = List.of();
        int repeated = 0;
        int repeatGrace = 0;
        int planContinues = 0;
        int outputLimitStreak = 0;
        LinkedHashSet<String> changedFiles = new LinkedHashSet<String>();
        ArrayList<String> checks = new ArrayList<String>();
        if (executionPolicy != null) {
            Set<String> priorPaths = executionPolicy.priorChangedPaths();
            if (priorPaths != null) {
                priorPaths.stream().filter(Objects::nonNull).map(String::strip)
                        .filter(path -> !path.isBlank()).forEach(changedFiles::add);
            }
            List<String> priorChecks = executionPolicy.priorChecks();
            if (priorChecks != null) {
                priorChecks.stream().filter(Objects::nonNull).map(String::strip)
                        .filter(check -> !check.isBlank()).forEach(checks::add);
            }
        }
        UsageTracker usage = new UsageTracker(events);
        DynamicPollTracker dynamicPolls = new DynamicPollTracker(config.repeatRemindAt(), config.repeatAbortAt());
        RuntimeGuidance runtimeGuidance = new RuntimeGuidance();

        try {
            for (int step = 1; step <= config.maxSteps(); step++) {
                if (cancellation.cancelled()) {
                    return cancel(messages, events);
                }
                ContextCompactor.dropStaleToolImages(messages, MAX_TOOL_IMAGE_MESSAGES);
                budget.apply(messages, contextLimit(), events);

                if (applySteering(messages, events, pendingInput.get())) {
                    // 真实用户纠正优先，上一状态推导出的内部催办可能已经过期。
                    runtimeGuidance.clear();
                }

                ModelTurn turn = callModel(messages, events, usage, runtimeGuidance);
                if (turn.stop() != null) {
                    if ("CANCELLED".equals(turn.stop().failureCode())) return turn.stop();
                    return finalizeFailure(messages, events, usage, turn.stop().failureCode(),
                            "模型请求未能完成；已有进度保留。", false);
                }
                ModelResponse response = turn.response();
                if (cancellation.cancelled()) {
                    return cancel(messages, events);
                }

                String content = response.content() == null ? "" : response.content();
                List<ToolCall> calls = response.toolCalls() == null ? List.of() : response.toolCalls();
                String correction = pendingInput.get();
                if (hasInput(correction)) {
                    // 模型返回时已经有纠正：旧方案的工具都不执行，补齐协议结果后重新规划。
                    messages.add(Message.assistant(content, calls));
                    skipCalls(messages, calls, 0, "用户已纠正当前任务，请根据新指令重新规划。");
                    applySteering(messages, events, correction);
                    safeCheckpoint(checkpoint, messages);
                    lastSignature = null;
                    lastOutcomes = List.of();
                    repeated = 0;
                    continue;
                }
                if (response.finishReason() == ModelResponse.FinishReason.LENGTH) {
                    outputLimitStreak++;
                    messages.add(Message.assistant(content, calls));
                    skipCalls(messages, calls, 0, "模型输出在工具参数完整返回前达到长度上限，本次调用未执行。");
                    if (outputLimitStreak >= 3) {
                        throw new ExecutionPolicy.Stop("OUTPUT_LIMIT",
                                "模型连续 3 次达到输出长度上限，已停止自动续写以避免重复消耗。");
                    }
                    runtimeGuidance.add("上一条响应因输出长度上限被截断。不要重复已经完成的内容；把剩余工作拆小，"
                            + "优先完成当前最小可验证步骤。若工作已完成，直接给出简洁最终答复。");
                    events.accept(new RunContinued(outputLimitStreak, 3, "模型输出被截断，按剩余工作继续"));
                    safeCheckpoint(checkpoint, messages);
                    continue;
                }
                outputLimitStreak = 0;
                if (response.finishReason() == ModelResponse.FinishReason.CONTENT_FILTER
                        || response.finishReason() == ModelResponse.FinishReason.ERROR) {
                    throw new ExecutionPolicy.Stop("MODEL_INCOMPLETE",
                            "模型未正常结束（" + response.finishReason() + "），现有进度已保留。");
                }
                // 请求成功后只落一条完整回复。预览过程中的片段不进历史，重试也不会粘成两段。
                if (!content.isBlank() && !calls.isEmpty()) {
                    events.accept(new AssistantDelta(content));
                }
                if (calls.isEmpty()) {
                    messages.add(Message.assistant(content, List.of()));
                    String summary = content.isBlank() ? "任务完成（模型未返回文本）" : content;
                    List<PlanItem> plan = planSnapshot();
                    int done = (int) plan.stream().filter(PlanItem::done).count();
                    List<String> openSteps = plan.stream()
                            .filter(item -> !item.done())
                            .map(PlanItem::step)
                            .toList();
                    // 计划是所有执行模式共享的完成事实。ExecutionPolicy 只增加模式级核验，
                    // 不能绕过未完成计划，也不能把它当作已经完成后再清空。
                    if (!openSteps.isEmpty() && planContinues < config.maxPlanContinues()) {
                        planContinues++;
                        runtimeGuidance.add(continuePrompt(openSteps));
                        events.accept(new RunContinued(planContinues, config.maxPlanContinues(),
                                "计划还有 " + openSteps.size() + " 项未完成，自动继续"));
                        safeCheckpoint(checkpoint, messages);
                        continue;
                    }
                    // 旧版本成功的定向检查不能证明当前源码；仅复跑策略明确选出的受限命令。
                    // 仍由 ToolExecutor 走权限审批、工作区边界、超时及变更追踪。
                    ToolCall reverification = executionPolicy == null ? null : executionPolicy.reverificationCall();
                    if (reverification != null) {
                        messages.add(Message.assistant("当前源码变化使先前验证失效，自动复跑定向检查。",
                                List.of(reverification)));
                        events.accept(new RunContinued(1, 1, "当前源码变化，自动复跑先前成功的定向检查"));
                        ToolStartGuard startGuard = new ToolStartGuard();
                        ToolExecutor.Outcome outcome;
                        if (!executionPolicy.permits(reverification)) {
                            outcome = new ToolExecutor.Outcome(reverification.name(),
                                    "SKIPPED: " + executionPolicy.denialReason(reverification), null, null);
                        } else {
                            executionPolicy.beforeTool(reverification);
                            outcome = toolExecutor.execute(reverification, events, startGuard);
                        }
                        recordToolOutcome(reverification, outcome, changedFiles, checks, messages);
                        flushToolImages(messages);
                        if (hasInput(startGuard.correction)) {
                            applySteering(messages, events, startGuard.correction);
                        }
                        safeCheckpoint(checkpoint, messages);
                        if (cancellation.cancelled()) return cancel(messages, events);
                        continue;
                    }
                    // 通用验证先于模式级 reviewer；有独立审查时跳过重复的模型自审。
                    CompletionGate.Nudge nudge = completionGate.evaluate(changedFiles, checks, plan, openSteps,
                            executionPolicy != null && executionPolicy.independentCompletionReview(changedFiles));
                    if (nudge != null) {
                        runtimeGuidance.add(nudge.prompt());
                        events.accept(new RunContinued(nudge.attempt(), nudge.max(), nudge.reason()));
                        safeCheckpoint(checkpoint, messages);
                        continue;
                    }
                    // 模式级核验只在共同完成门之后运行；Goal 可继续跨轮，Direct 可增加
                    // 针对性缺口，但都不得篡改上面的计划快照。
                    if (executionPolicy != null) {
                        String remaining = executionPolicy.review(List.copyOf(messages), changedFiles, checks);
                        if (cancellation.cancelled()) return cancel(messages, events);
                        if (remaining != null) {
                            runtimeGuidance.add(remaining);
                            events.accept(new RunContinued(1, 1, "核对目标后继续：" + ToolSupport.oneLine(remaining, 120)));
                            safeCheckpoint(checkpoint, messages);
                            continue;
                        }
                    }
                    // 发出完成事件之前与输入入口原子交接，确保已接收的纠正不会落在完成之后。
                    if (cancellation.cancelled()) return cancel(messages, events);
                    if (applySteering(messages, events, pendingInput.get()) || !completionClaim.getAsBoolean()) {
                        safeCheckpoint(checkpoint, messages);
                        continue;
                    }
                    // 模型给出纯文本就收尾，但计划没做完≠任务完成，把差距如实交给客户端
                    if (!content.isBlank()) events.accept(new AssistantDelta(content));
                    events.accept(new RunCompleted(summary, List.copyOf(changedFiles), List.copyOf(checks),
                            done, plan.size(), openSteps));
                    return new Result(content, null, conversation(messages));
                }

                messages.add(Message.assistant(content, calls));

                String signature = signature(calls);
                boolean awaitingDynamicObservation = dynamicPolls.canObserveAgain(signature, calls);

                ArrayList<ToolExecutor.Outcome> stepOutcomes = new ArrayList<ToolExecutor.Outcome>();
                boolean batchInterrupted = false;
                if (parallelReadBatch(calls)) {
                    String beforeBatch = pendingInput.get();
                    if (hasInput(beforeBatch)) {
                        skipCalls(messages, calls, 0, "用户已纠正当前任务，本次调用未执行，请重新规划。");
                        applySteering(messages, events, beforeBatch);
                        lastSignature = null;
                        lastOutcomes = List.of();
                        repeated = 0;
                        batchInterrupted = true;
                    } else {
                        AtomicReference<String> correctionDuringBatch = new AtomicReference<>();
                        Consumer<SessionEvent> serializedEvents = event -> {
                            synchronized (events) { events.accept(event); }
                        };
                        Supplier<String> parallelStartGuard = () -> {
                            if (cancellation.cancelled()) return "运行已暂停或取消，本次调用未执行。";
                            synchronized (pendingInput) {
                                if (hasInput(correctionDuringBatch.get())) {
                                    return "用户已纠正当前任务，本次调用未执行，请重新规划。";
                                }
                                String queuedCorrection = pendingInput.get();
                                if (hasInput(queuedCorrection)) {
                                    correctionDuringBatch.set(queuedCorrection);
                                    return "用户已纠正当前任务，本次调用未执行，请重新规划。";
                                }
                            }
                            return cancellation.cancelled() ? "运行已暂停或取消，本次调用未执行。" : null;
                        };
                        List<Boolean> permitted = new ArrayList<>(calls.size());
                        for (ToolCall call : calls) {
                            boolean allow = executionPolicy == null || executionPolicy.permits(call);
                            permitted.add(allow);
                            if (allow && executionPolicy != null) executionPolicy.beforeTool(call);
                        }
                        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
                            List<CompletableFuture<ToolExecutor.Outcome>> futures = new ArrayList<>(calls.size());
                            for (int index = 0; index < calls.size(); index++) {
                                ToolCall call = calls.get(index);
                                if (!permitted.get(index)) {
                                    futures.add(CompletableFuture.completedFuture(new ToolExecutor.Outcome(call.name(),
                                            "SKIPPED: " + executionPolicy.denialReason(call), null, null)));
                                } else {
                                    futures.add(CompletableFuture.supplyAsync(() -> toolExecutor.execute(
                                            call, serializedEvents, parallelStartGuard), workers));
                                }
                            }
                            for (int index = 0; index < calls.size(); index++) {
                                ToolExecutor.Outcome outcome = futures.get(index).join();
                                stepOutcomes.add(outcome);
                                recordToolOutcome(calls.get(index), outcome, changedFiles, checks, messages);
                            }
                        }
                        if (hasInput(correctionDuringBatch.get())) {
                            applySteering(messages, events, correctionDuringBatch.get());
                            lastSignature = null;
                            lastOutcomes = List.of();
                            repeated = 0;
                            batchInterrupted = true;
                        }
                    }
                } else {
                    for (int index = 0; index < calls.size(); index++) {
                        if (cancellation.cancelled()) {
                            skipCalls(messages, calls, index, "运行已暂停或取消，本次调用未执行。");
                            return cancel(messages, events);
                        }
                        String nextCorrection = pendingInput.get();
                        if (hasInput(nextCorrection)) {
                            skipCalls(messages, calls, index, "用户已纠正当前任务，本次调用未执行，请重新规划。");
                            applySteering(messages, events, nextCorrection);
                            lastSignature = null;
                            lastOutcomes = List.of();
                            repeated = 0;
                            batchInterrupted = true;
                            break;
                        }
                        ToolCall call = calls.get(index);
                        ToolStartGuard startGuard = new ToolStartGuard();
                        ToolExecutor.Outcome outcome;
                        if (executionPolicy != null && !executionPolicy.permits(call)) {
                            outcome = new ToolExecutor.Outcome(call.name(),
                                    "SKIPPED: " + executionPolicy.denialReason(call), null, null);
                        } else {
                            if (executionPolicy != null) executionPolicy.beforeTool(call);
                            outcome = toolExecutor.execute(call, events, startGuard);
                        }
                        stepOutcomes.add(outcome);
                        recordToolOutcome(call, outcome, changedFiles, checks, messages);
                        if (hasInput(startGuard.correction)) {
                            // 审批期间的纠正也要先补齐整批 tool 结果，再追加 user 消息。
                            skipCalls(messages, calls, index + 1, "用户已纠正当前任务，本次调用未执行，请重新规划。");
                            applySteering(messages, events, startGuard.correction);
                            lastSignature = null;
                            lastOutcomes = List.of();
                            repeated = 0;
                            batchInterrupted = true;
                            break;
                        }
                    }
                }
                flushToolImages(messages);
                if (!batchInterrupted) {
                    if (!awaitingDynamicObservation) {
                        List<String> observedResults = stepOutcomes.stream()
                                .map(ToolExecutor.Outcome::result).toList();
                        repeated = signature.equals(lastSignature)
                                && observedResults.equals(lastOutcomes) ? repeated + 1 : 1;
                        lastSignature = signature;
                        lastOutcomes = observedResults;
                        if (repeated == 1) repeatGrace = 0;
                        if (config.repeatRemindAt() > 0 && repeated == config.repeatRemindAt()) {
                            runtimeGuidance.add(repeatReminder(calls, repeated));
                            events.accept(new RunContinued(1, 1,
                                    "重复调用提醒：连续第 " + repeated + " 次无新结果"));
                        }
                        if (repeated >= config.repeatAbortAt()) {
                            if (repeatGrace >= MAX_REPEAT_GRACE) {
                                throw new ExecutionPolicy.Stop("REPEATED_TOOL_CALL",
                                        "连续 " + repeated + " 次相同工具调用得到相同结果，已中断无效循环。");
                            }
                            repeatGrace++;
                            runtimeGuidance.add(repeatFinalWarning(calls, repeated, repeatGrace));
                            events.accept(new RunContinued(repeatGrace, MAX_REPEAT_GRACE,
                                    "重复调用缓冲：连续第 " + repeated + " 次无新结果，要求换路"));
                        }
                    } else {
                        lastSignature = null;
                        lastOutcomes = List.of();
                        repeated = 0;
                    }
                    DynamicPollTracker.Decision decision = dynamicPolls.observe(signature, calls, stepOutcomes);
                    if (decision.remind()) {
                        runtimeGuidance.add(stuckReminder(calls, decision.unchangedCount(),
                                "后台任务仍处于运行中，状态与日志没有产生新观察"));
                        events.accept(new RunContinued(1, 1,
                                "后台状态连续 " + decision.unchangedCount() + " 次未变化，提示换路或等待"));
                    }
                    if (decision.abort()) {
                        throw new ExecutionPolicy.Stop("WAITING",
                                "后台任务连续 " + decision.unchangedCount()
                                        + " 次有界观察都没有新状态，已暂停高频轮询；稍后使用原 taskId 继续查询，"
                                        + "观察状态或日志是否发生变化。");
                    }
                }
                // 每步落盘，进程被打断时最多丢当前这一步
                safeCheckpoint(checkpoint, messages);
                if (cancellation.cancelled()) return cancel(messages, events);
            }

            return finalizeFailure(messages, events, usage, "MAX_STEPS",
                    "已达到本轮步数上限 " + config.maxSteps() + "，历史已保留，可以继续执行。", true);
        } catch (ExecutionPolicy.Stop stop) {
            if (cancellation.cancelled()) return cancel(messages, events);
            return finalizeFailure(messages, events, usage, stop.code(), stop.getMessage(), true);
        } finally {
            safeCheckpoint(checkpoint, messages);
        }
    }

    /** 非取消停止先做一次禁用工具的收尾，避免界面只剩失败状态。 */
    private Result finalizeFailure(List<Message> messages, Consumer<SessionEvent> events, UsageTracker usage,
                                   String code, String reason, boolean emitFailure) {
        String fallback = "本轮未能完整结束（" + code + "）：" + ToolSupport.oneLine(reason, 500)
                + ("NO_PROGRESS".equals(code)
                ? "\n\n已有进度已保存。请提供新信息，或在工作区出现实际变更后再继续。"
                : "\n\n已有进度已保存。请根据上面的原因补充条件或选择继续执行。");
        String finalText = fallback;
        if (!cancellation.cancelled() && !"NO_PROGRESS".equals(code)) {
            try {
                if (executionPolicy != null) executionPolicy.beginFinalization();
                refreshTaskContext(messages, null);
                ArrayList<Message> request = new ArrayList<>(messages);
                request.add(Message.user("[系统收尾要求] 本轮必须现在结束，禁止调用工具，也不要假装任务已经完成。"
                        + "请基于已有对话和证据给用户一份可直接阅读的阶段交付：先说明实际完成了什么和证据，"
                        + "再说明未完成项；最后写明停止原因与恢复所需条件。停止代码：" + code
                        + "；停止原因：" + reason));
                usage.start(request, contextLimit());
                ModelResponse response = client.chat(List.copyOf(request), List.of());
                usage.finish(response);
                if (response.content() != null && !response.content().isBlank()) finalText = response.content();
            } catch (RuntimeException ignored) {
                usage.abort();
            }
        }
        messages.add(Message.assistant(finalText, List.of()));
        events.accept(new AssistantDelta(finalText));
        if (emitFailure) events.accept(new RunFailed(code, reason, true));
        return new Result(finalText, code, conversation(messages));
    }

    private static boolean hasInput(String input) {
        return input != null && !input.isBlank();
    }

    private boolean parallelReadBatch(List<ToolCall> calls) {
        // 自定义权限门可能有状态或审批副作用；只有内置纯策略门可安全预检并并行。
        return permissionGate instanceof PolicyPermissionGate
                && calls.size() > 1 && calls.size() <= MAX_PARALLEL_READS
                && calls.stream().allMatch(call -> tools.find(call.name())
                        .map(tool -> tool.parallelSafe() && permissionGate.check(call, tool).isAllowed())
                        .orElse(false));
    }

    /** 工具产出的图片；等本批 tool 结果全部落进历史后再统一作为一条 user 消息发出。 */
    private final List<PendingToolImages> pendingToolImages = new ArrayList<>();

    private record PendingToolImages(ToolCall call, List<Attachment> images) { }

    /**
     * tool 消息只能是文本，图片必须排在整批 tool 结果之后，否则会拆开
     * assistant(tool_calls) 与它的 tool 结果，被模型协议拒绝。
     */
    private void flushToolImages(List<Message> messages) {
        if (pendingToolImages.isEmpty()) return;
        List<Attachment> images = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (PendingToolImages pending : pendingToolImages) {
            images.addAll(pending.images());
            sources.add(pending.call().name() + "(" + pending.call().callId() + ")："
                    + pending.images().stream().map(Attachment::name).collect(Collectors.joining(", ")));
        }
        pendingToolImages.clear();
        messages.add(Message.toolImages("以下图片由工具产出，对应上面的调用结果——" + String.join("；", sources), images));
    }

    private void recordToolOutcome(ToolCall call, ToolExecutor.Outcome outcome,
                                   Set<String> changedFiles, List<String> checks, List<Message> messages) {
        if (!outcome.result().startsWith("SKIPPED:")) {
            completionGate.recordTarget(call);
            completionGate.countToolCall();
        }
        changedFiles.addAll(outcome.changedPaths());
        for (String check : outcome.checks()) {
            if (!checks.contains(check)) checks.add(check);
        }
        messages.add(Message.tool(call.callId(), outcome.result()));
        if (!outcome.images().isEmpty()) pendingToolImages.add(new PendingToolImages(call, outcome.images()));
        if (executionPolicy != null) executionPolicy.observed(call, outcome);
    }

    /** 审批通过不等于仍可执行；开始产生副作用前再消费一次控制信号。 */
    private final class ToolStartGuard implements Supplier<String> {
        private String correction;

        @Override
        public String get() {
            if (cancellation.cancelled()) return "运行已暂停或取消，本次调用未执行。";
            correction = pendingInput.get();
            if (cancellation.cancelled()) return "运行已暂停或取消，本次调用未执行。";
            return hasInput(correction) ? "用户已纠正当前任务，本次调用未执行，请重新规划。" : null;
        }
    }

    private static boolean applySteering(List<Message> messages, Consumer<SessionEvent> events, String input) {
        if (!hasInput(input)) return false;
        messages.add(Message.user(input.strip()));
        events.accept(new RunSteered(input.strip()));
        return true;
    }

    /** assistant 的每个 tool_call 都必须有对应结果，否则暂停后续跑会被模型协议拒绝。 */
    private static void skipCalls(List<Message> messages, List<ToolCall> calls, int from, String reason) {
        for (int index = from; index < calls.size(); index++) {
            messages.add(Message.tool(calls.get(index).callId(), "SKIPPED: " + reason));
        }
    }

    private void refreshTaskContext(List<Message> messages, String runtimeGuidance) {
        String context = taskContext.get();
        StringBuilder system = new StringBuilder(config.systemPrompt());
        if (context != null && !context.isBlank()) system.append("\n\n").append(context);
        if (runtimeGuidance != null && !runtimeGuidance.isBlank()) {
            system.append("\n\n[Runtime guidance; not a new user request]\n")
                    .append(runtimeGuidance.strip());
        }
        messages.set(0, Message.system(system.toString()));
    }

    private static void safeCheckpoint(Consumer<List<Message>> checkpoint, List<Message> messages) {
        try {
            checkpoint.accept(conversation(messages));
        } catch (RuntimeException ignored) {
            // 记忆落盘失败不应中断本次运行
        }
    }

    /** 自动续跑时给模型的提醒：模板在 {@code prompts/plan/continue.md}。 */
    private static String continuePrompt(List<String> openSteps) {
        String steps = openSteps.stream()
                .map(step -> "- " + step)
                .collect(Collectors.joining("\n"));
        return PROMPTS.render("plan/continue", Map.of("openSteps", steps)).strip();
    }

    /** 重复调用提醒：模板在 {@code prompts/loop/repeat-reminder.md}。 */
    private static String repeatReminder(List<ToolCall> calls, int count) {
        String tools = calls.stream()
                .map(ToolCall::name)
                .distinct()
                .collect(Collectors.joining(", "));
        return PROMPTS.render("loop/repeat-reminder",
                Map.of("count", String.valueOf(count), "tool", tools)).strip();
    }

    /** 达到中断阈值后的缓冲提醒：明确告知再重复会被中断，要求换成能产生不同状态的动作。 */
    private static String repeatFinalWarning(List<ToolCall> calls, int count, int grace) {
        String tools = calls.stream().map(ToolCall::name).distinct().collect(Collectors.joining(", "));
        return PROMPTS.render("loop/stuck-nudge", Map.of(
                "pattern", "同一工具调用连续 " + count + " 次得到相同结果：" + tools,
                "repeatCount", String.valueOf(count),
                "unchangedState", "最近的工具结果与上一次完全相同；缓冲还剩 " + (MAX_REPEAT_GRACE - grace)
                        + " 次，用完后本轮会被中断",
                "blockedActions", "- 不要再次用相同参数调用 " + tools)).strip();
    }

    /** 动态任务仍在运行但观察没有变化时，要求模型换成有意义的等待/替代动作。 */
    private static String stuckReminder(List<ToolCall> calls, int count, String unchangedState) {
        String tools = calls.stream().map(ToolCall::name).distinct().collect(Collectors.joining(", "));
        return PROMPTS.render("loop/stuck-nudge", Map.of(
                "pattern", "重复观察后台工具：" + tools,
                "repeatCount", String.valueOf(count),
                "unchangedState", unchangedState,
                "blockedActions", "- 不要立刻用相同参数高频轮询\n- 不要重新启动同一个后台任务"
        )).strip();
    }

    /** 计划快照读失败不应影响收尾：退化成「无计划」，仅少一条完成度信息。 */
    private List<PlanItem> planSnapshot() {
        try {
            List<PlanItem> plan = config.plan().get();
            return plan == null ? List.of() : plan;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 断网、超时、429、5xx 先重试，最多 3 次，仍失败就结束运行。用户可以随时取消。
     * 401、403、404、400 这种重试也不会好的错误，立刻失败。
     */
    private ModelTurn callModel(List<Message> messages, Consumer<SessionEvent> events, UsageTracker usage,
                                RuntimeGuidance runtimeGuidance) {
        int attempt = 0;
        String retainedGuidance = null;
        while (true) {
            if (cancellation.cancelled()) {
                return new ModelTurn(null, cancel(messages, events));
            }
            try {
                // 用户纠正优先于策略提示；不要提前消费一次性 guidance 后再因为 steering 丢掉。
                if (applySteering(messages, events, pendingInput.get())) {
                    runtimeGuidance.clear();
                    retainedGuidance = null;
                    continue;
                }
                String policyGuidance = null;
                if (executionPolicy != null) {
                    executionPolicy.beforeRequest(List.copyOf(messages));
                    policyGuidance = executionPolicy.requestGuidance();
                }
                if (cancellation.cancelled()) return new ModelTurn(null, cancel(messages, events));
                // 本地控制提示与 policy guidance 都只属于这次采样。模型请求发生瞬时失败时
                // retainedGuidance 会沿重试保留；真正拿到响应后才消费。
                retainedGuidance = RuntimeGuidance.merge(retainedGuidance,
                        runtimeGuidance.render(policyGuidance));
                // 阶段性纠偏属于运行时上下文，只进入当前请求的 system 消息；下一次请求会
                // 重新构造 system，checkpoint/conversation 也从不保存它。
                refreshTaskContext(messages, retainedGuidance);
                usage.start(messages, contextLimit());
                ModelResponse response = client.chat(List.copyOf(messages), tools.definitions(), delta -> {
                    if (liveText == null || delta == null || delta.isEmpty()) {
                        return;
                    }
                    try {
                        liveText.accept(delta);
                    } catch (RuntimeException ignored) {
                        // 界面预览失败不影响这一次模型请求
                    }
                });
                usage.finish(response);
                runtimeGuidance.clear();
                // client 已拿到请求快照；立即还原基础 system，避免当前内存历史也携带一次性提示。
                refreshTaskContext(messages, null);
                return new ModelTurn(response, null);
            } catch (ExecutionPolicy.Stop stop) {
                usage.abort();
                throw stop;
            } catch (RuntimeException e) {
                usage.abort();
                if (cancellation.cancelled()) {
                    return new ModelTurn(null, cancel(messages, events));
                }
                if (!ModelRetryPolicy.isTransient(e)) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    throw new ExecutionPolicy.Stop("MODEL_ERROR",
                            "模型调用失败：" + ToolSupport.oneLine(reason, 300));
                }
                attempt++;
                if (attempt > ModelRetryPolicy.MAX_TRANSIENT_RETRIES) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    throw new ExecutionPolicy.Stop("MODEL_ERROR",
                            "模型暂时不可用，已重试 " + ModelRetryPolicy.MAX_TRANSIENT_RETRIES + " 次："
                                    + ToolSupport.oneLine(reason, 300));
                }
                long delayMs = ModelRetryPolicy.delayMs(attempt);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                events.accept(new RunRetrying(attempt, delayMs, ToolSupport.oneLine(reason, 300)));
                if (!pause(delayMs)) {
                    return new ModelTurn(null, cancel(messages, events));
                }
            }
        }
    }

    /** @return false 表示用户取消或线程被打断，调用方应结束运行 */
    private boolean pause(long delayMs) {
        long waited = 0;
        while (waited < delayMs) {
            if (cancellation.cancelled()) {
                return false;
            }
            long slice = Math.min(RETRY_SLICE_MS, delayMs - waited);
            try {
                retryPause.await(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            waited += slice;
        }
        return !cancellation.cancelled();
    }

    private Result cancel(List<Message> messages, Consumer<SessionEvent> events) {
        events.accept(new RunCancelled("运行已被用户取消。"));
        return new Result(null, "CANCELLED", conversation(messages));
    }

    private int contextLimit() {
        String override = System.getProperty("pilot.context.max-chars");
        if (override != null) {
            try {
                int value = Integer.parseInt(override.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 回落配置里的预算
            }
        }
        return config.contextLimit();
    }

    private static List<Message> conversation(List<Message> messages) {
        return List.copyOf(messages.subList(1, messages.size()));
    }

    private static String signature(List<ToolCall> calls) {
        return calls.stream()
                .map(call -> call.name() + "(" + canonical(call.args()) + ")")
                .collect(Collectors.joining(";"));
    }

    /**
     * 单次采样可见的运行时控制提示。它不进入 messages，因此不会伪装成用户意图，
     * checkpoint 与最终 conversation 也不会把它永久保存下来。
     */
    private static final class RuntimeGuidance {
        private final LinkedHashSet<String> pending = new LinkedHashSet<>();

        private void add(String value) {
            if (value != null && !value.isBlank()) pending.add(value.strip());
        }

        private void clear() {
            pending.clear();
        }

        private String render(String policyGuidance) {
            String local = pending.isEmpty() ? null : String.join("\n\n", pending);
            return merge(local, policyGuidance);
        }

        private static String merge(String first, String second) {
            boolean hasFirst = first != null && !first.isBlank();
            boolean hasSecond = second != null && !second.isBlank();
            if (!hasFirst) return hasSecond ? second.strip() : null;
            if (!hasSecond) return first.strip();
            String left = first.strip();
            String right = second.strip();
            return left.equals(right) || left.contains(right) ? left : left + "\n\n" + right;
        }
    }

    /**
     * 后台状态工具的重复参数代表“再次观察”，而不是静态重复读取。只有结果也连续不变时才计入停滞，
     * 且窗口仍然有上限，避免模型无限消耗请求轮询一个不会结束的任务。
     */
    private static final class DynamicPollTracker {
        private final int remindAt;
        private final int abortAt;
        private String signature;
        private String observation;
        private int unchangedCount;
        private boolean awaiting;

        private DynamicPollTracker(int remindAt, int ordinaryAbortAt) {
            this.remindAt = remindAt;
            long expanded = Math.max(MIN_DYNAMIC_POLL_ABORT, ordinaryAbortAt * 2L);
            this.abortAt = (int) Math.min(Integer.MAX_VALUE, expanded);
        }

        private boolean canObserveAgain(String nextSignature, List<ToolCall> calls) {
            return awaiting && nextSignature.equals(signature) && dynamicBatch(calls);
        }

        private Decision observe(String currentSignature, List<ToolCall> calls,
                                 List<ToolExecutor.Outcome> outcomes) {
            if (!dynamicBatch(calls) || outcomes.size() != calls.size()) {
                reset();
                return Decision.NONE;
            }
            boolean stillAwaiting = true;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                ToolExecutor.Outcome outcome = outcomes.get(i);
                boolean callAwaiting = outcome.status() == ToolExecutor.OutcomeStatus.RUNNING
                        || ("command_logs".equals(call.name())
                        && outcome.result() != null && outcome.result().contains("eof: false"));
                stillAwaiting &= callAwaiting;
                current.append(call.name()).append(':').append(outcome.status()).append(':')
                        .append(outcome.result()).append('\n');
            }
            if (!stillAwaiting) {
                reset();
                return Decision.NONE;
            }

            String currentObservation = current.toString();
            if (currentSignature.equals(signature) && currentObservation.equals(observation)) {
                unchangedCount++;
            } else {
                unchangedCount = 1;
            }
            signature = currentSignature;
            observation = currentObservation;
            awaiting = true;
            return new Decision(remindAt > 0 && unchangedCount == remindAt,
                    unchangedCount >= abortAt, unchangedCount);
        }

        private void reset() {
            signature = null;
            observation = null;
            unchangedCount = 0;
            awaiting = false;
        }

        private static boolean dynamicBatch(List<ToolCall> calls) {
            return !calls.isEmpty() && calls.stream().allMatch(call ->
                    "command_status".equals(call.name()) || "command_logs".equals(call.name()));
        }

        private record Decision(boolean remind, boolean abort, int unchangedCount) {
            private static final Decision NONE = new Decision(false, false, 0);
        }
    }

    private record ModelTurn(ModelResponse response, Result stop) {
    }

    /** 一次 run 内累计模型用量；请求开始先推估算，响应回来后再用端点 usage 校准。 */
    private static final class UsageTracker {
        private final Consumer<SessionEvent> events;
        private int requests;
        private long inputTokens;
        private long outputTokens;
        private long pendingInput;
        private int contextTokens;
        private int contextLimit = 1;
        private boolean allExact = true;

        private UsageTracker(Consumer<SessionEvent> events) {
            this.events = events;
        }

        private void start(List<Message> messages, int limit) {
            pendingInput = ContextCompactor.estimateTokens(messages);
            contextTokens = (int) Math.min(Integer.MAX_VALUE, pendingInput);
            contextLimit = Math.max(1, limit);
            requests++;
            publish(false);
        }

        private void finish(ModelResponse response) {
            ModelUsage reported = response == null ? ModelUsage.unknown() : response.usage();
            if (reported != null && reported.present()) {
                inputTokens += reported.inputTokens() > 0 ? reported.inputTokens() : pendingInput;
                outputTokens += reported.outputTokens();
                allExact = allExact && reported.exact() && reported.inputTokens() > 0;
            } else {
                inputTokens += pendingInput;
                outputTokens += estimateOutput(response);
                allExact = false;
            }
            pendingInput = 0;
            publish(allExact);
        }

        private void abort() {
            if (pendingInput <= 0) return;
            // 请求可能已经到达端点，但失败响应通常没有 usage；保留已发出的输入估算，
            // 避免界面在失败/重试时先增长又倒退，并明确降为非精确值。
            inputTokens += pendingInput;
            pendingInput = 0;
            allExact = false;
            publish(false);
        }

        private long estimateOutput(ModelResponse response) {
            if (response == null) return 0;
            long total = ContextCompactor.estimateTokens(response.content());
            for (ToolCall call : response.toolCalls() == null ? List.<ToolCall>of() : response.toolCalls()) {
                total += ContextCompactor.estimateTokens(call.name());
                total += ContextCompactor.estimateTokens(String.valueOf(call.args()));
            }
            return total;
        }

        private void publish(boolean exact) {
            long visibleInput = inputTokens + pendingInput;
            events.accept(new RunUsageUpdated(requests, visibleInput, outputTokens,
                    contextTokens, contextLimit, exact && pendingInput == 0));
        }
    }

    /** 重试前等待。测试传入立刻返回的实现，避免真的睡几秒。 */
    interface Pause {
        void await(long millis) throws InterruptedException;
    }

    /** 参数规范化：Map 按 key 排序，保证同样的调用得到同样的签名。 */
    private static String canonical(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> entry.getKey() + "=" + canonical(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentLoop::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }
}
