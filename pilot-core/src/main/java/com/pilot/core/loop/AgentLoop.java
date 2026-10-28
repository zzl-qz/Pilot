package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunSteered;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionGate;
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
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
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
     * @param repeatRemindAt       连续相同工具调用达到该次数时跳过执行并注入提醒，给模型纠错机会；
     *                              0 表示不提醒，达到 {@code repeatAbortAt} 直接中断（旧行为）
     * @param repeatAbortAt         连续相同工具调用达到该次数即中断（含第一次）
     * @param plan                  当前计划快照，收尾时用来判断「任务是否真的做完」；默认无计划
     * @param maxPlanContinues      计划还有未完成项时自动催模型继续的次数上限；0 表示不自动继续
     * @param contextLimit          上下文预算（按字符估算）；太小会频繁压缩、丢细节
     */
    public record Config(int maxSteps, int repeatRemindAt, int repeatAbortAt, String systemPrompt,
                         Supplier<List<PlanItem>> plan, int maxPlanContinues, int contextLimit,
                         boolean completionChecks) {
        /** 兼容旧调用：连续相同调用达到 {@code repeatedToolCallLimit} 即中断，不提醒。 */
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

        /** 渐进提醒：达到 {@code repeatRemindAt} 跳过执行并提醒，达到 {@code repeatAbortAt} 中断。 */
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
    /** 计划未完成时默认自动催模型继续的次数上限。 */
    private static final int DEFAULT_MAX_PLAN_CONTINUES = 3;
    /** 等待重试时拆成小段，取消能在这段时间内停下来。 */
    private static final long RETRY_SLICE_MS = 250L;

    private final ModelClient client;
    private final ToolRegistry tools;
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
        this.budget = new ContextBudget(new ContextCompactor(client)::compact);
        this.toolExecutor = new ToolExecutor(tools,
                Objects.requireNonNull(permissionGate, "permissionGate must not be null"),
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
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be blank");
        }
        List<Message> messages = seed(history);
        String goal = userInput.strip();
        this.completionGate = new CompletionGate(PROMPTS, config.completionChecks(), goal);
        messages.add(Message.user(goal, attachments == null ? List.of() : attachments));
        return loop(messages, events, checkpoint);
    }

    /** 续跑：不追加用户消息，直接用已有记忆继续，服务「跑一半」之后的恢复。 */
    public Result resume(List<Message> history, Consumer<SessionEvent> events,
                         Consumer<List<Message>> checkpoint) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        List<Message> messages = seed(history);
        if (messages.size() <= 1) {
            throw new IllegalArgumentException("history is empty, nothing to resume");
        }
        this.completionGate = new CompletionGate(PROMPTS, config.completionChecks(), null);
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
        int repeated = 0;
        int planContinues = 0;
        var changedFiles = new LinkedHashSet<String>();
        var checks = new ArrayList<String>();

        try {
            for (int step = 1; step <= config.maxSteps(); step++) {
                if (cancellation.cancelled()) {
                    return cancel(messages, events);
                }
                budget.apply(messages, contextLimit(), events);

                applySteering(messages, events, pendingInput.get());

                ModelTurn turn = callModel(messages, events);
                if (turn.stop() != null) {
                    return turn.stop();
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
                    repeated = 0;
                    continue;
                }
                // 请求成功后只落一条完整回复。预览过程中的片段不进历史，重试也不会粘成两段。
                if (!content.isBlank()) {
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
                    // 计划没做完就自动催一轮，别停下来问用户；到达上限才收尾，把差距交回界面
                    if (!openSteps.isEmpty() && planContinues < config.maxPlanContinues()) {
                        planContinues++;
                        messages.add(Message.user(continuePrompt(openSteps)));
                        events.accept(new RunContinued(planContinues, config.maxPlanContinues(),
                                "计划还有 " + openSteps.size() + " 项未完成，自动继续"));
                        safeCheckpoint(checkpoint, messages);
                        continue;
                    }
                    // 收尾门：改完不验证、不核对就交差，是「做得浅」的主要来源
                    CompletionGate.Nudge nudge = completionGate.evaluate(changedFiles, checks, plan, openSteps);
                    if (nudge != null) {
                        messages.add(Message.user(nudge.prompt()));
                        events.accept(new RunContinued(nudge.attempt(), nudge.max(), nudge.reason()));
                        safeCheckpoint(checkpoint, messages);
                        continue;
                    }
                    // 发出完成事件之前与输入入口原子交接，确保已接收的纠正不会落在完成之后。
                    if (cancellation.cancelled()) return cancel(messages, events);
                    if (applySteering(messages, events, pendingInput.get()) || !completionClaim.getAsBoolean()) {
                        safeCheckpoint(checkpoint, messages);
                        continue;
                    }
                    // 模型给出纯文本就收尾，但计划没做完≠任务完成，把差距如实交给客户端
                    events.accept(new RunCompleted(summary, List.copyOf(changedFiles), List.copyOf(checks),
                            done, plan.size(), openSteps));
                    return new Result(content, null, conversation(messages));
                }

                messages.add(Message.assistant(content, calls));

                String signature = signature(calls);
                if (signature.equals(lastSignature)) {
                    repeated++;
                } else {
                    lastSignature = signature;
                    repeated = 1;
                }
                if (repeated >= config.repeatAbortAt()) {
                    String detail = "模型连续 " + repeated + " 次请求相同的工具调用，已中断以避免死循环。";
                    skipCalls(messages, calls, 0, "已达到重复工具调用上限，本次调用未执行。");
                    events.accept(new RunFailed("REPEATED_TOOL_CALL", detail, false));
                    return new Result(null, "REPEATED_TOOL_CALL", conversation(messages));
                }
                // 达到提醒档：跳过执行，合成结果与提醒给模型纠错机会；提醒只注入一次
                if (config.repeatRemindAt() > 0 && repeated >= config.repeatRemindAt()) {
                    for (ToolCall call : calls) {
                        messages.add(Message.tool(call.callId(),
                                "已跳过执行：这是连续第 " + repeated + " 次发出完全相同的调用（"
                                        + call.name() + "），结果不会与上一次不同。"));
                    }
                    if (repeated == config.repeatRemindAt()) {
                        messages.add(Message.user(repeatReminder(calls, repeated)));
                        events.accept(new RunContinued(1, 1,
                                "重复调用提醒：连续第 " + repeated + " 次，已跳过执行"));
                    }
                    safeCheckpoint(checkpoint, messages);
                    continue;
                }

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
                        repeated = 0;
                        break;
                    }
                    ToolCall call = calls.get(index);
                    var startGuard = new ToolStartGuard();
                    ToolExecutor.Outcome outcome = toolExecutor.execute(call, events, startGuard);
                    if (!outcome.result().startsWith("SKIPPED:")) {
                        completionGate.recordTarget(call);
                        completionGate.countToolCall();
                    }
                    if (outcome.changedPath() != null) {
                        changedFiles.add(outcome.changedPath());
                    }
                    if (outcome.check() != null) {
                        checks.add(outcome.check());
                    }
                    messages.add(Message.tool(call.callId(), outcome.result()));
                    if (hasInput(startGuard.correction)) {
                        // 审批期间的纠正也要先补齐整批 tool 结果，再追加 user 消息。
                        skipCalls(messages, calls, index + 1, "用户已纠正当前任务，本次调用未执行，请重新规划。");
                        applySteering(messages, events, startGuard.correction);
                        lastSignature = null;
                        repeated = 0;
                        break;
                    }
                }
                // 每步落盘，进程被打断时最多丢当前这一步
                safeCheckpoint(checkpoint, messages);
                if (cancellation.cancelled()) return cancel(messages, events);
            }

            events.accept(new RunFailed("MAX_STEPS",
                    "已达到本轮步数上限 " + config.maxSteps() + "，历史已保留，可以继续执行。", true));
            return new Result(null, "MAX_STEPS", conversation(messages));
        } finally {
            safeCheckpoint(checkpoint, messages);
        }
    }

    private static boolean hasInput(String input) {
        return input != null && !input.isBlank();
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

    private void refreshTaskContext(List<Message> messages) {
        String context = taskContext.get();
        messages.set(0, Message.system(config.systemPrompt()
                + (context == null || context.isBlank() ? "" : "\n\n" + context)));
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
    private ModelTurn callModel(List<Message> messages, Consumer<SessionEvent> events) {
        int attempt = 0;
        while (true) {
            if (cancellation.cancelled()) {
                return new ModelTurn(null, cancel(messages, events));
            }
            try {
                refreshTaskContext(messages);
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
                return new ModelTurn(response, null);
            } catch (RuntimeException e) {
                if (cancellation.cancelled()) {
                    return new ModelTurn(null, cancel(messages, events));
                }
                if (!ModelRetryPolicy.isTransient(e)) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    events.accept(new RunFailed("MODEL_ERROR",
                            "模型调用失败：" + ToolSupport.oneLine(reason, 300), true));
                    return new ModelTurn(null, new Result(null, "MODEL_ERROR", conversation(messages)));
                }
                attempt++;
                if (attempt > ModelRetryPolicy.MAX_TRANSIENT_RETRIES) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    events.accept(new RunFailed("MODEL_ERROR",
                            "模型暂时不可用，已重试 " + ModelRetryPolicy.MAX_TRANSIENT_RETRIES + " 次："
                                    + ToolSupport.oneLine(reason, 300), true));
                    return new ModelTurn(null, new Result(null, "MODEL_ERROR", conversation(messages)));
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

    private record ModelTurn(ModelResponse response, Result stop) {
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
