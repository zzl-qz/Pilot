package com.pilot.core.loop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunEnded;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.RunOutcome;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunSteered;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.exec.ExecutionBackends.Selection;
import com.pilot.core.hook.Hooks;
import com.pilot.core.lint.LintGate;
import com.pilot.core.lint.SourceFiles;
import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.model.ModelCapacity;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelFailureKind;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.RequestBudget;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.quality.IndependentCodeReviewer;
import com.pilot.core.quality.TextChange;
import com.pilot.core.tool.ReadToolOutputTool;
import com.pilot.core.tool.ToolCallJournal;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.trace.TraceRecorder;
import com.pilot.core.util.Texts;
import com.pilot.core.util.WireEnum;

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
     *
     * <p>按空工具集过滤：默认场景没有注册任何工具，条件注册的段落（技能目录、子代理、
     * 并行派发）随之消失——工具不存在，用法教学就不进提示词，也无需提供技能目录变量。
     */
    public static final String DEFAULT_SYSTEM_PROMPT = PROMPTS.system(PromptLibrary.environment(null), Set.of());

    /**
     * @param repeatRemindAt       相同调用连续得到相同结果达到该次数时注入提醒；0 表示不提醒
     * @param repeatAbortAt         保留兼容：重复调用不再中断运行
     * @param plan                  当前计划快照；启用完成检查时未完成项阻止成功收尾
     * @param maxPlanContinues      保留兼容字段；完成门固定最多提醒两次
     * @param contextLimit          上下文预算（按字符估算）；太小会频繁压缩、丢细节
     */
    public record Config(int maxSteps, int repeatRemindAt, int repeatAbortAt, String systemPrompt,
        Supplier<List<PlanItem>> plan, int maxPlanContinues, int contextLimit, boolean completionChecks) {
        /** 兼容旧调用：连续无新结果达到 {@code repeatedToolCallLimit} 即中断，不提醒。 */
        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt, Supplier<List<PlanItem>> plan,
            int maxPlanContinues, int contextLimit, boolean completionChecks) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, plan, maxPlanContinues, contextLimit,
                completionChecks);
        }

        /** 兼容旧调用：收尾门默认开启。 */
        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt, Supplier<List<PlanItem>> plan,
            int maxPlanContinues, int contextLimit) {
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
                throw new IllegalArgumentException("repeatRemindAt must be 0 (disabled) or less than repeatAbortAt");
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
        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt, Supplier<List<PlanItem>> plan,
            int maxPlanContinues) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, plan, maxPlanContinues, DEFAULT_CONTEXT_LIMIT, true);
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt, Supplier<List<PlanItem>> plan) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, plan, DEFAULT_MAX_PLAN_CONTINUES,
                DEFAULT_CONTEXT_LIMIT, true);
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt) {
            this(maxSteps, 0, repeatedToolCallLimit, systemPrompt, List::of, DEFAULT_MAX_PLAN_CONTINUES,
                DEFAULT_CONTEXT_LIMIT, true);
        }

        /** 渐进提醒：达到 {@code repeatRemindAt} 提醒，达到 {@code repeatAbortAt} 中断。 */
        public Config(int maxSteps, int repeatRemindAt, int repeatAbortAt, String systemPrompt) {
            this(maxSteps, repeatRemindAt, repeatAbortAt, systemPrompt, List::of, DEFAULT_MAX_PLAN_CONTINUES,
                DEFAULT_CONTEXT_LIMIT, true);
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
     * @param changedFiles 本轮改过的文件（失败/部分交付也要保留）
     * @param checks       本轮跑过的验证项（失败/部分交付也要保留）
     * @param plan         运行结束时的计划快照
     */
    public record Result(String finalText, String failureCode, List<Message> conversation, List<String> changedFiles,
        List<String> checks, List<PlanItem> plan) {
        public Result {
            changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
            checks = List.copyOf(checks == null ? List.of() : checks);
            plan = List.copyOf(plan == null ? List.of() : plan);
        }

        /** 兼容只关心文本/失败码/历史的调用方。 */
        public Result(String finalText, String failureCode, List<Message> conversation) {
            this(finalText, failureCode, conversation, List.of(), List.of(), List.of());
        }

        public boolean failed() {
            return failureCode != null;
        }

        public RunOutcome outcome() {
            return RunOutcome.fromCode(failureCode);
        }
    }

    private static final int DEFAULT_CONTEXT_LIMIT = 120_000;
    /** 动态后台状态即使暂时不变也可能稍后完成；给它比普通重复调用更宽的有界观察窗口。 */
    private static final int MIN_DYNAMIC_POLL_ABORT = 8;
    private static final int MAX_PARALLEL_READS = 10;
    /** 预算压力下保留最近几条带图片的工具观察；更早的换成文字说明。 */
    private static final int MAX_TOOL_IMAGE_MESSAGES = 3;
    /** 低水位不逐轮改写旧工具图以保护 KV 前缀缓存，仅保留纯内存硬上限保险丝。 */
    private static final int MAX_TOOL_IMAGE_MESSAGES_HARD = 12;
    /** 计划未完成时默认自动催模型继续的次数上限。 */
    private static final int DEFAULT_MAX_PLAN_CONTINUES = 3;
    /** 等待重试时拆成小段，取消能在这段时间内停下来。 */
    private static final long RETRY_SLICE_MS = 250L;
    /** 同一轮里允许因复读中止并重试的次数；第二次仍然复读说明不是偶发抖动。 */
    private static final int MAX_REPEAT_ABORTS = 2;
    /** 同一请求里允许模型因非法工具调用 JSON 纠偏重试的最大次数。 */
    private static final int MAX_MALFORMED_TOOL_RETRIES = 3;
    /** 同一地址连续这么多次快照都没有可点击元素，就停止验收，不再重开页面。 */
    private static final int MAX_EMPTY_BROWSER_SNAPSHOTS = 3;
    /** 阶段尾部候选提示默认存活的模型请求次数；进入探索或首次编辑后立即清退以保持前缀 append_only。 */
    static final int DEFAULT_PHASE_HINT_TURNS = 2;
    private static final Set<String> PHASE_HINT_EXPLORE_TOOLS =
        Set.of(ToolNames.READ_FILE, ToolNames.SEARCH_TEXT, ToolNames.GLOB_FILES, "code_symbol", "file_outline");
    private static final String REPEAT_GUIDANCE = "上一次回复陷入逐行重复（同一行连续出现多次），已中止。" + "请重新组织答案：不要重复相同句子或列表项，直接推进未完成的部分。";
    private static final String MALFORMED_TOOL_GUIDANCE =
        "上一次响应的工具调用参数不是合法 JSON，本次调用未执行。请重新输出严格合法的 JSON 对象参数（注意转义双引号与换行），或将较大补丁拆小后分次提交。";

    private final ModelClient client;
    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    /** 上下文预算的四层收缩策略与退避状态。 */
    private final ContextBudget budget;
    /** 本 run 是否已观测到端点缓存命中；兼容网关据此决定摘要是否值得复用前缀。 */
    private final AtomicBoolean observedCacheHit = new AtomicBoolean();
    private final ToolExecutor toolExecutor;
    private final Config config;
    private final Cancellation cancellation;
    private Supplier<Message> pendingInput;
    private final TraceRecorder traceRecorder;
    private final Pause retryPause;
    /** 正在生成的文字，只给界面看。为空表示不推预览。 */
    private final Consumer<String> liveText;
    /** 模型推理增量，只给界面看，不进会话历史。 */
    private Consumer<String> liveReasoning;
    /** 收尾门：有了目标之后在 run/resume 里创建，一次运行一个。 */
    private CompletionGate completionGate;
    private String projectConstraints = "";
    private final List<String> touchedPaths = new ArrayList<>();
    private int emptyBrowserSnapshots;
    private int idleDedupRounds;
    private Function<List<String>, String> scopedRules = paths -> "";
    private ExecutionPolicy executionPolicy;
    private Hooks hooks = Hooks.none();
    private Runnable contextShrinkListener = () -> {
    };
    private volatile PhaseHint phaseHint;
    private Function<String, String> steeringHintProvider;

    /** 上下文发生裁剪或摘要压缩时回调（用于失效已交付读取视图，防止重读被「文件未变化」卡死）。 */
    public AgentLoop withContextShrinkListener(Runnable listener) {
        this.contextShrinkListener = listener == null ? () -> {
        } : listener;
        return this;
    }

    /** 阶段级非持久化尾部引导（如候选定位点）：不落盘、不改历史前缀，命中候选读取、首次编辑或耗尽轮次后自动退场。 */
    public AgentLoop withInitialPhaseHint(String hint) {
        return withInitialPhaseHint(hint, Set.of());
    }

    /** 带候选路径集合的阶段引导：首轮一旦开始读取任意候选文件即清退，避免后续回合尾部反复移位。 */
    public AgentLoop withInitialPhaseHint(String hint, Set<String> candidatePaths) {
        refreshPhaseHint(hint, candidatePaths);
        return this;
    }

    /** 用户 steering 纠正时按新输入重算阶段尾部引导。 */
    public AgentLoop withSteeringHintProvider(Function<String, String> provider) {
        this.steeringHintProvider = provider;
        return this;
    }

    /** 刷新当前阶段尾部引导；传入空文本等价于清空。 */
    public void refreshPhaseHint(String hint) {
        refreshPhaseHint(hint, Set.of());
    }

    /** 刷新当前阶段尾部引导及对应候选路径集合；传入空文本等价于清空。 */
    public void refreshPhaseHint(String hint, Set<String> candidatePaths) {
        if (hint == null || hint.isBlank()) {
            this.phaseHint = null;
            return;
        }
        Set<String> normalized = candidatePaths == null ? Set.of() : candidatePaths.stream().filter(Objects::nonNull)
            .map(AgentLoop::normalizeHintPath).filter(p -> !p.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.phaseHint = new PhaseHint(hint.strip(), DEFAULT_PHASE_HINT_TURNS, normalized);
    }

    /** 清空当前阶段尾部引导。 */
    public void clearPhaseHint() {
        this.phaseHint = null;
    }

    /** 返回本轮已读取或修改过的路径快照（供外层排除已读候选和统计命中率）。 */
    public Set<String> observedPathsSnapshot() {
        synchronized (touchedPaths) {
            return Set.copyOf(touchedPaths);
        }
    }

    /** 把模型的推理增量推到界面。不影响最终回答。 */
    public AgentLoop withLiveReasoning(Consumer<String> reasoning) {
        liveReasoning = reasoning;
        return this;
    }

    /** 引导与普通输入一样保留图片及其在正文中的位置。仅在启动循环前配置。 */
    public AgentLoop withPendingMessages(Supplier<Message> input) {
        pendingInput = Objects.requireNonNull(input);
        return this;
    }

    public AgentLoop withExecutionPolicy(ExecutionPolicy policy) {
        executionPolicy = Objects.requireNonNull(policy);
        return this;
    }

    private Supplier<Set<String>> restoredPaths = Set::of;
    /** 本轮写前文本；null 表示文件原本不存在。读取失败由接入层抛异常。 */
    private Function<String, String> originalFileText = path -> null;
    /** 当前工作区文本；null 表示文件已删除或无法按文本读取。 */
    private Function<String, String> currentFileText = path -> null;
    private Path qualityWorkspace;
    private LintGate lintGate = LintGate.enabled();
    private IndependentCodeReviewer independentCodeReviewer;

    /** 接入层用本轮写前快照确认已经还原的路径；历史调用记录仍保留。 */
    public AgentLoop withRestoredPaths(Supplier<Set<String>> paths) {
        restoredPaths = Objects.requireNonNull(paths);
        return this;
    }

    /**
     * 接入代码质量收尾门。两个读取器都按工作区相对路径返回全文；当前文本读不到时返回 {@code null}。
     */
    public AgentLoop withQualityTexts(Function<String, String> originalText, Function<String, String> currentText) {
        originalFileText = Objects.requireNonNull(originalText, "original text reader must not be null");
        currentFileText = Objects.requireNonNull(currentText, "current text reader must not be null");
        lintGate.setOriginalText(originalFileText);
        return this;
    }

    /** 工作区根目录。收尾 lint 在这里发现并执行仓库自己的检查命令。 */
    public AgentLoop withQualityWorkspace(Path workspace) {
        qualityWorkspace = workspace;
        toolExecutor.withLint(lintGate, qualityWorkspace);
        return this;
    }

    /** 收尾 lint 开关。默认开启；进程配置关掉时传入 {@link LintGate#disabled()}。 */
    public AgentLoop withLintGate(LintGate lintGate) {
        this.lintGate = lintGate == null ? LintGate.disabled() : lintGate;
        this.lintGate.setOriginalText(originalFileText);
        toolExecutor.withLint(this.lintGate, qualityWorkspace);
        return this;
    }

    /** 当前项目在界面里写下的约束。空字符串表示这个项目没有额外约束。 */
    public AgentLoop withProjectConstraints(String text) {
        projectConstraints = text == null ? "" : text.strip();
        return this;
    }

    /** 接入轻量独立语义代码审查器，仅在改动达到阈值且测试通过后最多运行一次。 */
    public AgentLoop withIndependentCodeReviewer(IndependentCodeReviewer reviewer) {
        independentCodeReviewer = reviewer;
        return this;
    }

    /** 工具调用幂等账本：重启接管后同一参数写文件调用不重复落盘。 */
    public AgentLoop withToolJournal(String runId, ToolCallJournal journal) {
        this.toolExecutor.withJournal(runId, journal);
        if (journal != null && tools.find(ToolNames.READ_TOOL_OUTPUT).isEmpty()) {
            tools.register(new ReadToolOutputTool(journal));
        }
        return this;
    }

    /** 确定性钩子：编辑后自动格式化/lint 之类的事情交给配置，不占用模型注意力。 */
    public AgentLoop withHooks(Hooks hooks) {
        this.hooks = hooks == null ? Hooks.none() : hooks;
        // 执行器在构造时已经建好，这里补挂一次，否则配置的钩子不会生效
        this.toolExecutor.withHooks(this.hooks);
        return this;
    }

    public AgentLoop withHookExecution(Selection execution) {
        this.toolExecutor.withHookExecution(execution);
        return this;
    }

    private Supplier<String> taskContext = () -> "";
    private BooleanSupplier completionClaim = () -> true;
    /** 模型失败重试的退避、预算与熔断；可在构造后替换以测试或按配置调参。 */
    private RetryBudget retryBudget = RetryBudget.fromSystemProperties();
    /** 计划门：长任务前段一直没有 update_plan 时提醒一次。 */
    private final PlanGate planGate = new PlanGate();
    /** 同一端点连续连接级失败的次数；成功一次就清零，避免跨故障累积。 */
    private int connectionFailures;

    /** 替换重试治理；替换后从干净状态开始。 */
    public AgentLoop withRetryBudget(RetryBudget budget) {
        retryBudget = Objects.requireNonNull(budget, "retry budget must not be null");
        return this;
    }

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

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        Config config) {
        this(client, tools, permissionGate, approvals, config, Cancellation.none());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        Config config, Cancellation cancellation) {
        this(client, tools, permissionGate, approvals, config, cancellation, () -> null);
    }

    /**
     * @param pendingInput 在模型请求和工具执行之间拉取当前任务纠正；返回 null 表示没有
     */
    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        Config config, Cancellation cancellation, Supplier<String> pendingInput) {
        this(client, tools, permissionGate, approvals, config, cancellation, pendingInput,
            (call, result, latencyMs) -> {
            }, (Pause)Thread::sleep, null);
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        Config config, Cancellation cancellation, Supplier<String> pendingInput, TraceRecorder traceRecorder) {
        this(client, tools, permissionGate, approvals, config, cancellation, pendingInput, traceRecorder, Thread::sleep,
            null);
    }

    /**
     * @param liveText 模型正在吐的字。只更新界面，不进会话历史。
     */
    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        Config config, Cancellation cancellation, Supplier<String> pendingInput, TraceRecorder traceRecorder,
        Consumer<String> liveText) {
        this(client, tools, permissionGate, approvals, config, cancellation, pendingInput, traceRecorder, Thread::sleep,
            liveText);
    }

    /**
     * @param retryPause 重试前的等待。生产环境睡觉，测试里记下来立刻返回。
     */
    AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        Config config, Cancellation cancellation, Supplier<String> pendingInput, TraceRecorder traceRecorder,
        Pause retryPause, Consumer<String> liveText) {
        Objects.requireNonNull(pendingInput, "pendingInput must not be null");
        this.pendingInput = () -> {
            String text = pendingInput.get();
            return text == null || text.isBlank() ? null : Message.user(text.strip());
        };
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
        this.retryPause = retryPause == null ? Thread::sleep : retryPause;
        this.liveText = liveText;
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.budget = new ContextBudget(
            new ContextCompactor(client).withTools(tools::definitions).withTokenLimit(this::admissionLimit)
                .withCharLimit(this::contextLimit).withObservedCacheHit(observedCacheHit::get)::compact);
        this.toolExecutor = new ToolExecutor(tools, this.permissionGate,
            Objects.requireNonNull(approvals, "approvals must not be null"), traceRecorder, hooks);
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
    }

    public Result run(String userInput, List<Message> history, Consumer<SessionEvent> events) {
        return run(userInput, history, events, messages -> {
        });
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
        List<Message> history, Consumer<SessionEvent> events, Consumer<List<Message>> checkpoint) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be blank");
        }
        List<Message> messages = seed(history);
        String goal = userInput.strip();
        this.completionGate = new CompletionGate(PROMPTS, config.completionChecks(), goal, lintGate);
        this.completionGate.useProjectConstraints(projectConstraints);
        lintGate.lock(qualityWorkspace);
        messages.add(Message.user(goal, attachments == null ? List.of() : attachments,
            imagePositions == null ? List.of() : imagePositions));
        return loop(messages, events, checkpoint);
    }

    /** 续跑：不追加用户消息，直接用已有记忆继续，服务「跑一半」之后的恢复。 */
    public Result resume(List<Message> history, Consumer<SessionEvent> events, Consumer<List<Message>> checkpoint) {
        return resume(history, events, checkpoint, null);
    }

    /**
     * 带原始目标续跑。服务层从持久任务契约传入 goal，使只读调研的完成门在进程重启或
     * 用户显式继续后仍能按同一个目标核对覆盖范围。
     */
    public Result resume(List<Message> history, Consumer<SessionEvent> events, Consumer<List<Message>> checkpoint,
        String goal) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        List<Message> messages = seed(history);
        RequestBudget.closeDanglingToolCalls(messages);
        if (messages.size() <= 1) {
            throw new IllegalArgumentException("history is empty, nothing to resume");
        }
        this.completionGate = new CompletionGate(PROMPTS, config.completionChecks(), goal, lintGate);
        this.completionGate.useProjectConstraints(projectConstraints);
        lintGate.lock(qualityWorkspace);
        this.completionGate.restoreFrom(history);
        return loop(messages, events, checkpoint);
    }

    private List<Message> seed(List<Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(config.systemPrompt()));
        if (history != null) {
            messages.addAll(history);
        }
        observedThroughIndex = ContextCompactor.observedPrefix(messages);
        return messages;
    }

    /** 单轮运行共享的会话、检查结果与输出入口；每次 loop 都重新创建。 */
    private record LoopContext(List<Message> messages, Consumer<SessionEvent> events,
        Consumer<List<Message>> checkpoint, Set<String> changedFiles, List<String> checks,
        RuntimeGuidance runtimeGuidance, Set<String> inheritedPaths) {
    }

    private record ParallelBatch(List<ToolExecutor.Outcome> outcomes, Message correction) {
    }

    private Result loop(List<Message> messages, Consumer<SessionEvent> events, Consumer<List<Message>> checkpoint) {
        String lastSignature = null;
        List<String> lastOutcomes = List.of();
        int repeated = 0;
        int outputLimitStreak = 0;
        LinkedHashSet<String> changedFiles = new LinkedHashSet<String>();
        ArrayList<String> checks = new ArrayList<String>();
        if (executionPolicy != null) {
            Set<String> priorPaths = executionPolicy.priorChangedPaths();
            if (priorPaths != null) {
                priorPaths.stream().filter(Objects::nonNull).map(String::strip).filter(path -> !path.isBlank())
                    .forEach(changedFiles::add);
            }
            List<String> priorChecks = executionPolicy.priorChecks();
            if (priorChecks != null) {
                priorChecks.stream().filter(Objects::nonNull).map(String::strip).filter(check -> !check.isBlank())
                    .forEach(checks::add);
            }
            completionGate.baseline(List.copyOf(checks));
        }
        UsageTracker usage = new UsageTracker(events, this::noteUsage);
        DynamicPollTracker dynamicPolls = new DynamicPollTracker(config.repeatRemindAt(), config.repeatAbortAt());
        planGate.reset();
        RuntimeGuidance runtimeGuidance = new RuntimeGuidance();
        LoopContext context = new LoopContext(messages, events, checkpoint, changedFiles, checks, runtimeGuidance,
            Set.copyOf(changedFiles));

        try {
            for (int step = 1; step <= config.maxSteps(); step++) {
                if (cancellation.cancelled()) {
                    return stopForCancellation(messages, events);
                }
                int beforeCompression = messages.size();
                int beforeChars = ContextCompactor.totalChars(messages);
                ContextCompactor.dropStaleToolImages(messages, MAX_TOOL_IMAGE_MESSAGES_HARD, observedThroughIndex);
                budget.apply(messages, admissionLimit(), contextLimit(), fixedOverheadTokens(messages), events,
                    observedThroughIndex);
                observedThroughIndex = Math.max(0, observedThroughIndex - (beforeCompression - messages.size()));
                if (ContextCompactor.totalChars(messages) < beforeChars) {
                    notifyContextShrink();
                }

                if (applySteering(messages, events, pendingInput.get())) {
                    // 真实用户纠正优先，上一状态推导出的内部催办可能已经过期。
                    runtimeGuidance.clear();
                }

                int remainingSteps = config.maxSteps() - step + 1;
                if (remainingSteps <= Math.min(2, Math.max(1, config.maxSteps() / 4))) {
                    runtimeGuidance.add(PROMPTS.render("loop/budget-approaching", Map.of()));
                }

                ModelTurn turn = callModel(messages, events, usage, runtimeGuidance);
                if (turn.stop() != null) {
                    if (RunFailureCode.CANCELLED.is(turn.stop().failureCode())
                        || RunFailureCode.BUDGET_EXHAUSTED.is(turn.stop().failureCode())) {
                        return turn.stop();
                    }
                    if (RunFailureCode.CONTEXT_TOO_LARGE.is(turn.stop().failureCode())) {
                        messages.add(Message.assistant(turn.stop().finalText(), List.of()));
                        events.accept(new AssistantDelta(turn.stop().finalText()));
                        events.accept(new RunFailed(RunFailureCode.CONTEXT_TOO_LARGE, turn.stop().finalText(), false));
                        return new Result(turn.stop().finalText(), turn.stop().failureCode(), conversation(messages));
                    }
                    return finalizeFailure(messages, events, usage, turn.stop().failureCode(), "模型请求未能完成；已有进度保留。",
                        false, changedFiles, checks);
                }
                ModelResponse response = turn.response();
                if (cancellation.cancelled()) {
                    return stopForCancellation(messages, events);
                }

                if (response.finishReason() == ModelResponse.FinishReason.CONTENT_FILTER
                    || response.finishReason() == ModelResponse.FinishReason.ERROR
                    || response.finishReason() == ModelResponse.FinishReason.UNKNOWN) {
                    throw new ExecutionPolicy.Stop(RunFailureCode.MODEL_INCOMPLETE,
                        "模型未正常结束（" + response.finishReason() + "），现有进度已保留。");
                }
                String content = response.content() == null ? "" : response.content();
                String reasoning = response.reasoningContent() == null ? "" : response.reasoningContent();
                List<ToolCall> calls = response.toolCalls() == null ? List.of() : response.toolCalls();
                planGate.observeToolCalls(calls.size());
                Message correction = pendingInput.get();
                if (hasInput(correction)) {
                    // 模型返回时已经有纠正：旧方案的工具都不执行，补齐协议结果后重新规划。
                    messages.add(Message.assistant(content, calls, reasoning));
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
                    messages.add(Message.assistant(content, calls, reasoning));
                    skipCalls(messages, calls, 0, "模型输出在工具参数完整返回前达到长度上限，本次调用未执行。");
                    if (outputLimitStreak >= 3) {
                        throw new ExecutionPolicy.Stop(RunFailureCode.OUTPUT_LIMIT, "模型连续 3 次达到输出长度上限，已停止自动续写以避免重复消耗。");
                    }
                    // 被截断的中间稿作废：留着会和下一轮重新生成的内容拼成重复段落。
                    events.accept(AssistantPreview.discarded());
                    runtimeGuidance.add("上一条响应因输出长度上限被截断。不要重复已经完成的内容；把剩余工作拆小，" + "优先完成当前最小可验证步骤。若工作已完成，直接给出简洁最终答复。");
                    events.accept(new RunContinued(outputLimitStreak, 3, "模型输出被截断，按剩余工作继续"));
                    safeCheckpoint(checkpoint, messages);
                    continue;
                }
                outputLimitStreak = 0;
                // 带工具调用的正文属于中间轮次，不是给用户的答复；只提交最终轮正文。
                if (calls.isEmpty()) {
                    Result completed = completeTurn(content, reasoning, context);
                    if (completed != null) {
                        return completed;
                    }
                    continue;
                }

                messages.add(Message.assistant(content, calls, reasoning));

                String signature = signature(calls);
                boolean awaitingDynamicObservation = dynamicPolls.canObserveAgain(signature, calls);

                ArrayList<ToolExecutor.Outcome> stepOutcomes = new ArrayList<ToolExecutor.Outcome>();
                boolean batchInterrupted = false;
                int index = 0;
                while (index < calls.size() && !batchInterrupted) {
                    if (cancellation.cancelledBeforeTool()) {
                        skipCalls(messages, calls, index, "运行已暂停或取消，本次调用未执行。");
                        return stopForCancellation(messages, events);
                    }
                    Message nextCorrection = pendingInput.get();
                    if (hasInput(nextCorrection)) {
                        skipCalls(messages, calls, index, "用户已纠正当前任务，本次调用未执行，请重新规划。");
                        applySteering(messages, events, nextCorrection);
                        lastSignature = null;
                        lastOutcomes = List.of();
                        repeated = 0;
                        batchInterrupted = true;
                        break;
                    }
                    int group = parallelRun(calls, index);
                    if (group > 1) {
                        ParallelBatch batch = executeParallelReads(calls.subList(index, index + group), context);
                        stepOutcomes.addAll(batch.outcomes());
                        if (hasInput(batch.correction())) {
                            skipCalls(messages, calls, index + group, "用户已纠正当前任务，本次调用未执行，请重新规划。");
                            applySteering(messages, events, batch.correction());
                            lastSignature = null;
                            lastOutcomes = List.of();
                            repeated = 0;
                            batchInterrupted = true;
                        }
                        index += group;
                        continue;
                    }
                    {
                        ToolCall call = calls.get(index);
                        ToolStartGuard startGuard = new ToolStartGuard();
                        ToolExecutor.Outcome outcome;
                        if (executionPolicy != null && !executionPolicy.permits(call)) {
                            outcome = new ToolExecutor.Outcome(call.name(),
                                ToolResults.skipped(executionPolicy.denialReason(call)), null, null);
                        } else {
                            if (executionPolicy != null) {
                                executionPolicy.beforeTool(call);
                            }
                            try {
                                outcome = toolExecutor.execute(call, events, startGuard);
                            } catch (RuntimeException e) {
                                outcome = new ToolExecutor.Outcome(call.name(), ToolResults.skipped("工具执行被中断，没有写回结果。"),
                                    null, null);
                            }
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
                        index++;
                    }
                }
                flushToolImages(messages);
                stopIfObservationWentIdle(stepOutcomes);
                if (!batchInterrupted) {
                    if (!awaitingDynamicObservation) {
                        List<String> observedResults = stepOutcomes.stream().map(ToolExecutor.Outcome::result).toList();
                        repeated =
                            signature.equals(lastSignature) && observedResults.equals(lastOutcomes) ? repeated + 1 : 1;
                        lastSignature = signature;
                        lastOutcomes = observedResults;
                        // 重复调用只提醒、不中断：是否换理由模型自己判断，运行不再因此失败。
                        if (config.repeatRemindAt() > 0 && repeated >= config.repeatRemindAt()) {
                            runtimeGuidance.add(repeatReminder(calls, repeated));
                            events.accept(new RunContinued(1, 1, "重复调用提醒：连续第 " + repeated + " 次无新结果"));
                        }
                    } else {
                        lastSignature = null;
                        lastOutcomes = List.of();
                        repeated = 0;
                    }
                    DynamicPollTracker.Decision decision = dynamicPolls.observe(signature, calls, stepOutcomes);
                    if (decision.remind()) {
                        runtimeGuidance.add(stuckReminder(calls, decision.unchangedCount(), "后台任务仍处于运行中，状态与日志没有产生新观察"));
                        events.accept(new RunContinued(1, 1, "后台状态连续 " + decision.unchangedCount() + " 次未变化，提示换路或等待"));
                    }
                    if (decision.abort()) {
                        throw new ExecutionPolicy.Stop(RunFailureCode.WAITING, "后台任务连续 " + decision.unchangedCount()
                            + " 次有界观察都没有新状态，已暂停高频轮询；稍后使用原 taskId 继续查询，" + "观察状态或日志是否发生变化。");
                    }
                }
                // 每步落盘，进程被打断时最多丢当前这一步
                safeCheckpoint(checkpoint, messages);
                if (cancellation.cancelled()) {
                    return stopForCancellation(messages, events);
                }
            }

            return finalizeFailure(messages, events, usage, RunFailureCode.MAX_STEPS.name(),
                "已达到本轮步数上限 " + config.maxSteps() + "，历史已保留，可以继续执行。", true, changedFiles, checks);
        } catch (ExecutionPolicy.Stop stop) {
            if (cancellation.cancelled()) {
                return stopForCancellation(messages, events);
            }
            return finalizeFailure(messages, events, usage, stop.code(), stop.getMessage(), true, changedFiles, checks);
        } finally {
            safeCheckpoint(checkpoint, messages);
        }
    }

    /** 返回 null 表示需要下一轮；只有完成门、模式审查和输入交接均通过才结束。 */
    private Result completeTurn(String content, String reasoning, LoopContext context) {
        List<Message> messages = context.messages();
        Consumer<SessionEvent> events = context.events();
        Consumer<List<Message>> checkpoint = context.checkpoint();
        Set<String> changedFiles = context.changedFiles();
        List<String> checks = context.checks();
        RuntimeGuidance runtimeGuidance = context.runtimeGuidance();
        messages.add(Message.assistant(content, List.of(), reasoning));
        String summary = content.isBlank() ? "任务完成（模型未返回文本）" : content;
        List<PlanItem> plan = planSnapshot();
        int done = (int)plan.stream().filter(PlanItem::done).count();
        List<String> openSteps = plan.stream().filter(PlanItem::open).map(PlanItem::step).toList();
        List<String> blockedSteps = plan.stream().filter(PlanItem::blocked).map(PlanItem::step).toList();
        // 启用完成检查时，未完成计划必须继续或明确交付部分结果。
        // 旧版本成功的定向检查不能证明当前源码；仅复跑策略明确选出的受限命令。
        // 仍由 ToolExecutor 走权限审批、工作区边界、超时及变更追踪。
        ToolCall reverification = executionPolicy == null ? null : executionPolicy.reverificationCall();
        if (reverification != null) {
            return reverifyCandidate(reverification, context);
        }
        // 只能用写前快照排除本轮已经还原的文件，不能相信模型声称撤销或 git status 为空。
        // 续跑继承的修改无法由本轮基线证明还原，仍保留并要求验证。
        try {
            Set<String> restored = restoredPaths.get();
            if (restored != null) {
                changedFiles.removeIf(path -> restored.contains(path) && !context.inheritedPaths().contains(path));
            }
        } catch (RuntimeException ignored) {
            // 无法核验时保守保留改动，继续走验证门。
        }
        // 通用验证先于模式级 reviewer；有独立审查时跳过重复的模型自审。
        List<String> changed = List.copyOf(changedFiles);
        List<String> checksOut = List.copyOf(checks);
        CompletionGate.Nudge nudge = completionGate.evaluate(changedFiles, checks, plan, openSteps,
            executionPolicy != null && executionPolicy.independentCompletionReview(changedFiles),
            qualityChanges(changedFiles), qualityWorkspace, independentCodeReviewer, content);
        if (nudge != null && !nudge.halt()
            && CompletionPressure.tooFull(messages, tools.definitions(), contextLimit())) {
            fitHistoryToInputBudget(messages, events, null);
            if (CompletionPressure.tooFull(messages, tools.definitions(), contextLimit())) {
                content = content + "\n\n上下文接近上限，已跳过非阻断收尾催办。";
                messages.set(messages.size() - 1, Message.assistant(content, List.of(), reasoning));
                events.accept(new RunContinued(1, 1, "上下文接近上限，已跳过非阻断收尾催办"));
                nudge = null;
            }
        }
        if (nudge != null) {
            if (nudge.halt()) {
                String partial = content + "\n\n收尾门禁未通过，已停止：" + nudge.reason();
                messages.set(messages.size() - 1, Message.assistant(partial, List.of(), reasoning));
                events.accept(RunEnded.of(RunOutcome.BLOCKED, RunFailureCode.TASK_INCOMPLETE.name(), partial, changed,
                    checksOut, plan, List.of("修复规范检查问题后继续")));
                return new Result(partial, RunFailureCode.TASK_INCOMPLETE.name(), conversation(messages), changed,
                    checksOut, plan);
            }
            runtimeGuidance.add(nudge.prompt());
            events.accept(new RunContinued(nudge.attempt(), nudge.max(), nudge.reason()));
            safeCheckpoint(checkpoint, messages);
            return null;
        }
        // 模式级核验只在共同完成门之后运行；Goal 可继续跨轮，Direct 可增加
        // 针对性缺口，但都不得篡改上面的计划快照。
        if (executionPolicy != null) {
            String remaining = executionPolicy.review(List.copyOf(messages), changedFiles, checks);
            if (cancellation.cancelled()) {
                return stopForCancellation(messages, events);
            }
            if (remaining != null) {
                runtimeGuidance.add(remaining);
                events.accept(new RunContinued(1, 1, "核对目标后继续：" + Texts.oneLine(remaining, 120)));
                safeCheckpoint(checkpoint, messages);
                return null;
            }
        }
        // 发出完成事件之前与输入入口原子交接，确保已接收的纠正不会落在完成之后。
        if (cancellation.cancelled()) {
            return stopForCancellation(messages, events);
        }
        if (applySteering(messages, events, pendingInput.get()) || !completionClaim.getAsBoolean()) {
            safeCheckpoint(checkpoint, messages);
            return null;
        }
        String lintNote = lintGate.releaseNote();
        if (lintNote != null && !lintNote.isBlank() && !lintGate.stopsOnFailure()) {
            content = content + "\n\n规范检查未通过，已记录并放行：\n" + lintNote;
            messages.set(messages.size() - 1, Message.assistant(content, List.of(), reasoning));
            summary = content;
        }
        if (config.completionChecks() && (!openSteps.isEmpty() || !blockedSteps.isEmpty())) {
            String partial =
                content + "\n\n本轮为部分交付，尚未完成：" + String.join("；", openSteps.isEmpty() ? blockedSteps : openSteps);
            if (!blockedSteps.isEmpty() && !openSteps.isEmpty()) {
                partial = partial + "\n\n已阻塞：" + String.join("；", blockedSteps);
            }
            messages.set(messages.size() - 1, Message.assistant(partial, List.of(), reasoning));
            events.accept(new AssistantDelta(partial));
            events.accept(RunEnded.of(RunOutcome.PARTIAL, RunFailureCode.TASK_INCOMPLETE.name(), partial, changed,
                checksOut, plan, List.of("查看本轮差异", "继续执行未完成步骤")));
            return new Result(partial, RunFailureCode.TASK_INCOMPLETE.name(), conversation(messages), changed,
                checksOut, plan);
        }
        // 仅在剩余计划与验收检查通过后发出完成事件。
        if (!content.isBlank()) {
            events.accept(new AssistantDelta(content));
        }
        events.accept(new RunCompleted(summary, changed, checksOut, done, plan.size(), openSteps));
        return new Result(content, null, conversation(messages), changed, checksOut, plan);
    }

    private Result reverifyCandidate(ToolCall reverification, LoopContext context) {
        List<Message> messages = context.messages();
        Consumer<SessionEvent> events = context.events();
        Consumer<List<Message>> checkpoint = context.checkpoint();
        Set<String> changedFiles = context.changedFiles();
        List<String> checks = context.checks();
        messages.add(Message.assistant("当前源码变化使先前验证失效，自动复跑定向检查。", List.of(reverification)));
        events.accept(new RunContinued(1, 1, "当前源码变化，自动复跑先前成功的定向检查"));
        ToolStartGuard startGuard = new ToolStartGuard();
        ToolExecutor.Outcome outcome;
        if (!executionPolicy.permits(reverification)) {
            outcome = new ToolExecutor.Outcome(reverification.name(),
                ToolResults.skipped(executionPolicy.denialReason(reverification)), null, null);
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
        if (cancellation.cancelled()) {
            return stopForCancellation(messages, events);
        }
        return null;
    }

    /** 虚拟线程只并行执行只读调用；结果仍按请求顺序记录，保持消息协议顺序。 */
    private ParallelBatch executeParallelReads(List<ToolCall> calls, LoopContext context) {
        List<Message> messages = context.messages();
        Consumer<SessionEvent> events = context.events();
        Set<String> changedFiles = context.changedFiles();
        List<String> checks = context.checks();
        List<ToolExecutor.Outcome> stepOutcomes = new ArrayList<>();
        AtomicReference<Message> correctionDuringBatch = new AtomicReference<>();
        Consumer<SessionEvent> serializedEvents = event -> {
            synchronized (events) {
                events.accept(event);
            }
        };
        Supplier<String> parallelStartGuard = () -> {
            if (cancellation.cancelledBeforeTool()) {
                return "运行已暂停或取消，本次调用未执行。";
            }
            synchronized (pendingInput) {
                if (hasInput(correctionDuringBatch.get())) {
                    return "用户已纠正当前任务，本次调用未执行，请重新规划。";
                }
                Message queuedCorrection = pendingInput.get();
                if (hasInput(queuedCorrection)) {
                    correctionDuringBatch.set(queuedCorrection);
                    return "用户已纠正当前任务，本次调用未执行，请重新规划。";
                }
            }
            return cancellation.cancelledBeforeTool() ? "运行已暂停或取消，本次调用未执行。" : null;
        };
        List<Boolean> permitted = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            boolean allow = executionPolicy == null || executionPolicy.permits(call);
            permitted.add(allow);
            if (allow && executionPolicy != null) {
                executionPolicy.beforeTool(call);
            }
        }
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ToolExecutor.Outcome>> futures = new ArrayList<>(calls.size());
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                if (!permitted.get(index)) {
                    futures.add(CompletableFuture.completedFuture(new ToolExecutor.Outcome(call.name(),
                        ToolResults.skipped(executionPolicy.denialReason(call)), null, null)));
                } else {
                    futures.add(CompletableFuture
                        .supplyAsync(() -> toolExecutor.execute(call, serializedEvents, parallelStartGuard), workers));
                }
            }
            for (int index = 0; index < calls.size(); index++) {
                ToolExecutor.Outcome outcome;
                try {
                    outcome = futures.get(index).join();
                } catch (RuntimeException e) {
                    outcome = new ToolExecutor.Outcome(calls.get(index).name(), ToolResults.skipped("工具执行被中断，没有写回结果。"),
                        null, null);
                }
                stepOutcomes.add(outcome);
                recordToolOutcome(calls.get(index), outcome, changedFiles, checks, messages);
            }
        }
        return new ParallelBatch(stepOutcomes, correctionDuringBatch.get());
    }

    /** 非模型故障可请求一次禁用工具的收尾；端点失败或预算耗尽时直接保留本地说明。 */
    private Result finalizeFailure(List<Message> messages, Consumer<SessionEvent> events, UsageTracker usage,
        String code, String reason, boolean emitFailure, Set<String> changedFiles, List<String> checks) {
        String fallback = "本轮未能完整结束（" + code + "）：" + Texts.oneLine(reason, 500) + (RunFailureCode.NO_PROGRESS.is(code)
            ? "\n\n已有进度已保存。可以先继续一次推进未完成步骤，或直接补充要执行的命令与资料后重新发送。" : "\n\n已有进度已保存。请根据上面的原因补充条件或选择继续执行。");
        String finalText = fallback;
        if (!cancellation.cancelled() && !WireEnum.in(code, RunFailureCode.NO_PROGRESS, RunFailureCode.MODEL_ERROR,
            RunFailureCode.MODEL_INCOMPLETE)) {
            try {
                if (executionPolicy != null) {
                    executionPolicy.beginFinalization();
                }
                refreshTaskContext(messages, null);
                ArrayList<Message> request = new ArrayList<>(messages);
                request.add(
                    Message.user("[系统收尾要求] 本轮必须现在结束，禁止调用工具，也不要假装任务已经完成。" + "请基于已有对话和证据给用户一份可直接阅读的阶段交付：先说明实际完成了什么和证据，"
                        + "再说明未完成项；最后写明停止原因与恢复所需条件。停止代码：" + code + "；停止原因：" + reason));
                if (!RequestBudget.assessRequest(request, List.of(), contextLimit()).fits()) {
                    throw new IllegalStateException("收尾请求超出安全上下文预算");
                }
                usage.start(request, contextLimit());
                ModelResponse response = client.chat(List.copyOf(request), List.of());
                usage.finish(response);
                if (response.finishReason() == ModelResponse.FinishReason.STOP
                    && (response.toolCalls() == null || response.toolCalls().isEmpty()) && response.content() != null
                    && !response.content().isBlank()) {
                    finalText = response.content();
                }
            } catch (RuntimeException ignored) {
                usage.abort();
                // 收尾请求失败同样要清掉中断标记，避免池化线程带着它影响后续任务
                Thread.interrupted();
            }
        }
        messages.add(Message.assistant(finalText, List.of()));
        events.accept(new AssistantDelta(finalText));
        List<PlanItem> plan = planSnapshot();
        List<String> changed = List.copyOf(changedFiles);
        List<String> checksOut = List.copyOf(checks);
        RunOutcome outcome = RunOutcome.fromCode(code);
        if (emitFailure) {
            if (outcome.failure()) {
                events.accept(new RunFailed(code, reason, true));
            } else {
                events.accept(RunEnded.of(outcome, code, finalText, changed, checksOut, plan, List.of("根据停止原因处理后继续")));
            }
        }
        return new Result(finalText, code, conversation(messages), changed, checksOut, plan);
    }

    private static boolean hasInput(Message input) {
        return input != null && (!input.content().isBlank() || input.hasAttachments());
    }

    /**
     * 从下标开始，连续可并行的调用有几个。写文件、打补丁和命令返回 1，单独执行。
     * 路障后面的只读调用会再开一组。自定义权限门不预检，整段退回逐个执行。
     */
    private int parallelRun(List<ToolCall> calls, int from) {
        if (!(permissionGate instanceof PolicyPermissionGate) || !parallelSafe(calls.get(from))) {
            return 1;
        }
        int count = 0;
        for (int index = from; index < calls.size() && count < MAX_PARALLEL_READS; index++) {
            if (!parallelSafe(calls.get(index))) {
                break;
            }
            count++;
        }
        return count;
    }

    private boolean parallelSafe(ToolCall call) {
        return tools.find(call.name()).map(tool -> tool.parallelSafe() && permissionGate.check(call, tool).isAllowed())
            .orElse(false);
    }

    /** 工具产出的图片；等本批 tool 结果全部落进历史后再统一作为一条 user 消息发出。 */
    private final List<PendingToolImages> pendingToolImages = new ArrayList<>();
    /** Exclusive message index that was included in the last successful model request. */
    private int observedThroughIndex;

    private record PendingToolImages(ToolCall call, List<Attachment> images) {
    }

    /**
     * tool 消息只能是文本，图片必须排在整批 tool 结果之后，否则会拆开
     * assistant(tool_calls) 与它的 tool 结果，被模型协议拒绝。
     */
    private void flushToolImages(List<Message> messages) {
        if (pendingToolImages.isEmpty()) {
            return;
        }
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

    private void recordToolOutcome(ToolCall call, ToolExecutor.Outcome outcome, Set<String> changedFiles,
        List<String> checks, List<Message> messages) {
        if (!ToolResults.isSkipped(outcome.result())) {
            completionGate.recordTarget(call);
            completionGate.countToolCall();
            completionGate.recordOutcome(outcome.changedPaths(), outcome.checks());
        }
        changedFiles.addAll(outcome.changedPaths());
        synchronized (touchedPaths) {
            touchedPaths.addAll(outcome.changedPaths());
            Object readPath = call.args() == null ? null : call.args().get("path");
            if (readPath != null && !readPath.toString().isBlank()) {
                touchedPaths.add(readPath.toString());
            }
        }
        if (!outcome.changedPaths().isEmpty()) {
            clearPhaseHint();
        } else if (!ToolResults.isError(outcome.result()) && !ToolResults.isSkipped(outcome.result())) {
            clearPhaseHintIfExplored(call);
        }
        for (String check : outcome.checks()) {
            if (!checks.contains(check)) {
                checks.add(check);
            }
        }
        messages.add(Message.tool(call.callId(), outcome.result()));
        stopIfBrowserHasNothingToClick(outcome.result());
        if (!outcome.images().isEmpty()) {
            pendingToolImages.add(new PendingToolImages(call, outcome.images()));
        }
        if (executionPolicy != null) {
            executionPolicy.observed(call, outcome);
        }
    }

    /** 快照连续三次都是 0 个可点击元素时停手。读文件、跑检查这种重复不在这里中断。 */
    private void stopIfBrowserHasNothingToClick(String result) {
        if (result == null || !result.contains("可交互元素:")) {
            return;
        }
        if (result.contains("可交互元素: 0")) {
            emptyBrowserSnapshots++;
            if (emptyBrowserSnapshots >= MAX_EMPTY_BROWSER_SNAPSHOTS) {
                throw new ExecutionPolicy.Stop(RunFailureCode.NO_PROGRESS,
                    "浏览器连续 " + emptyBrowserSnapshots + " 次打开的页面都没有可点击元素，验收停在这里。不要重复打开同一地址。");
            }
            return;
        }
        emptyBrowserSnapshots = 0;
    }

    /** 连续多轮全都是「没变化」的短结果，说明没有新信息，停下来。 */
    private void stopIfObservationWentIdle(List<ToolExecutor.Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        boolean idle = outcomes.stream().allMatch(outcome -> {
            String result = outcome.result();
            return result != null && (result.startsWith("文件未变化") || result.startsWith("与上次相同，未再执行"));
        });
        if (!idle) {
            idleDedupRounds = 0;
            return;
        }
        idleDedupRounds++;
        if (idleDedupRounds >= 4) {
            throw new ExecutionPolicy.Stop(RunFailureCode.NO_PROGRESS,
                "连续 " + idleDedupRounds + " 轮没有新观察，验收停在这里。请直接根据已有信息收尾，或改一个还没试过的做法。");
        }
    }

    private boolean recordToolObservationBoundary(List<Message> messages) {
        int pruned = ContextCompactor.pruneToolOutputs(messages, 0, observedThroughIndex);
        int truncated = ContextCompactor.truncateLongToolOutputs(messages, observedThroughIndex);
        return pruned > 0 || truncated > 0;
    }

    /**
     * 发送前把历史收进安全输入预算。
     * 先收掉已经看过的长工具输出。系统提示和工具定义还放得下时，先做一次交接摘要，
     * 再按组丢掉最早的对话。摘要也腾不出位置，才整组丢弃。
     */
    private void fitHistoryToInputBudget(List<Message> messages, Consumer<SessionEvent> events, String guidance) {
        int before = ContextCompactor.totalChars(messages);
        // 只有在当前请求放不下时才裁剪旧观察。每步都裁剪会把历史中间的消息改掉，
        // 供应商的 prefix cache 从第一个被改的 token 起全部失效；能放下就保持原样追加。
        if (!requestFits(messages, guidance)) {
            recordToolObservationBoundary(messages);
            ContextCompactor.dropStaleToolImages(messages, MAX_TOOL_IMAGE_MESSAGES, observedThroughIndex);
            ContextCompactor.truncateLongToolOutputs(messages, messages.size());
        }
        if (!requestFits(messages, guidance) && historyCanShrink(messages)) {
            int wireLimit = (int)Math.min(Integer.MAX_VALUE, RequestBudget
                .assessRequest(requestMessages(messages, guidance), tools.definitions(), contextLimit()).inputLimit());
            budget.apply(messages, Math.max(1, wireLimit), contextLimit(), fixedOverheadTokens(messages), events,
                observedThroughIndex);
            observedThroughIndex = Math.min(observedThroughIndex, messages.size());
        }
        int guard = messages.size() + 1;
        while (guard-- > 0 && !requestFits(messages, guidance)) {
            int chars = ContextCompactor.totalChars(messages);
            int tokens = ContextCompactor.estimateTokens(messages);
            int dropped = ContextCompactor.dropOldestGroups(messages, Math.max(8_000, chars * 2 / 3),
                Math.max(2_000, tokens * 2 / 3));
            if (dropped == 0) {
                break;
            }
            observedThroughIndex = Math.min(observedThroughIndex, messages.size());
        }
        // 只剩最近几组、又没有任何一条长到截断阈值时，整组丢弃会停住。再差一点就中断任务。
        // 这里改截短最长的历史正文，或去掉最早的工具图片，直到放得下。系统提示不动。
        shaveUntilFits(messages, guidance);
        if (ContextCompactor.totalChars(messages) < before) {
            notifyContextShrink();
            events.accept(new RunContinued(1, 1, "上下文超过安全输入预算，已折叠更早的记录并继续"));
        }
    }

    /**
     * 分组丢弃已经到保底、单条又不够长到截断阈值时，把最长的历史正文裁短。
     * 仍放不下就去掉最早的工具图片。系统提示不裁，裁到没处可裁就停。
     */
    private void shaveUntilFits(List<Message> messages, String guidance) {
        int guard = 24;
        while (guard-- > 0 && !requestFits(messages, guidance)) {
            int index = longestShrinkable(messages);
            if (index >= 0) {
                Message message = messages.get(index);
                String content = message.content();
                int keep = Math.max(400, content.length() / 2);
                int tail = Math.max(120, keep / 3);
                int head = Math.max(120, keep - tail);
                if (head + tail >= content.length() - 80) {
                    index = -1;
                } else {
                    String trimmed =
                        content.substring(0, head) + "\n[中间已截断以留出上下文]\n" + content.substring(content.length() - tail);
                    messages.set(index, new Message(message.role(), trimmed, message.toolCalls(), message.toolCallId(),
                        message.attachments(), message.imagePositions(), message.reasoningContent()));
                    continue;
                }
            }
            if (!dropOldestImage(messages)) {
                return;
            }
        }
    }

    /** 跳过系统提示。正文已经很短时返回 -1，避免把同一条裁到没有信息。 */
    private static int longestShrinkable(List<Message> messages) {
        int index = -1;
        int length = 400;
        for (int i = 1; i < messages.size(); i++) {
            Message message = messages.get(i);
            if ("system".equals(message.role()) || message.content().length() <= length) {
                continue;
            }
            length = message.content().length();
            index = i;
        }
        return index;
    }

    /** 图片按张计 token，文字裁短不够时先拿掉最早的一张。 */
    private static boolean dropOldestImage(List<Message> messages) {
        for (int i = 1; i < messages.size(); i++) {
            Message message = messages.get(i);
            boolean image = message.attachments().stream().anyMatch(Attachment::image);
            if (!image && !message.isToolImages()) {
                continue;
            }
            messages.set(i, new Message(message.role(), message.content() + "\n[图片已从本次请求移除以留出上下文]", message.toolCalls(),
                message.toolCallId(), List.of(), List.of(), message.reasoningContent()));
            return true;
        }
        return false;
    }

    /** 系统提示和工具定义本身已经超窗时，折叠历史没有用。 */
    private boolean historyCanShrink(List<Message> messages) {
        if (messages.isEmpty()) {
            return false;
        }
        return RequestBudget.assessRequest(List.of(messages.get(0)), tools.definitions(), contextLimit()).fits();
    }

    private boolean requestFits(List<Message> messages, String guidance) {
        return RequestBudget.assessRequest(requestMessages(messages, guidance), tools.definitions(), contextLimit())
            .fits();
    }

    /** 审批通过不等于仍可执行；开始产生副作用前再消费一次控制信号。 */
    private final class ToolStartGuard implements Supplier<String> {
        private Message correction;

        @Override
        public String get() {
            if (cancellation.cancelledBeforeTool()) {
                return "运行已暂停或取消，本次调用未执行。";
            }
            correction = pendingInput.get();
            if (cancellation.cancelledBeforeTool()) {
                return "运行已暂停或取消，本次调用未执行。";
            }
            return hasInput(correction) ? "用户已纠正当前任务，本次调用未执行，请重新规划。" : null;
        }
    }

    /**
     * 收尾前读取本轮仍保留的代码文本。读取失败的文件不加入结果，避免一次快照异常阻断任务。
     */
    private Map<String, TextChange> qualityChanges(Set<String> changedFiles) {
        if (changedFiles.isEmpty() || originalFileText == null) {
            return Map.of();
        }
        Map<String, TextChange> changes = new LinkedHashMap<>();
        for (String path : changedFiles) {
            if (!SourceFiles.isProductionSource(path)) {
                continue;
            }
            try {
                String before = originalFileText.apply(path);
                String after = currentFileText == null ? null : currentFileText.apply(path);
                if (after != null) {
                    changes.put(path, new TextChange(before, after));
                }
            } catch (RuntimeException ignored) {
                // 单个文件不可读时继续检查其余文件，不因此中断收尾。
            }
        }
        return Map.copyOf(changes);
    }

    private boolean applySteering(List<Message> messages, Consumer<SessionEvent> events, Message input) {
        if (!hasInput(input)) {
            return false;
        }
        messages.add(input);
        if (executionPolicy != null) {
            executionPolicy.onSteering();
        }
        if (steeringHintProvider != null) {
            try {
                refreshPhaseHint(steeringHintProvider.apply(input.content()));
            } catch (RuntimeException ignored) {
                clearPhaseHint();
            }
        } else {
            clearPhaseHint();
        }
        events.accept(new RunSteered(input.content(), input.attachments(), input.imagePositions()));
        return true;
    }

    /** assistant 的每个 tool_call 都必须有对应结果，否则暂停后续跑会被模型协议拒绝。 */
    private static void skipCalls(List<Message> messages, List<ToolCall> calls, int from, String reason) {
        for (int index = from; index < calls.size(); index++) {
            messages.add(Message.tool(calls.get(index).callId(), ToolResults.skipped(reason)));
        }
    }

    private void refreshTaskContext(List<Message> messages, String runtimeGuidance) {
        String context = taskContext.get();
        // 系统提示词保持稳定，运行时任务状态作为追加的 user reminder 进入历史。
        // 这样每次状态变化只改尾部，不会重写 system 前缀，也不会破坏供应商的 prefix cache。
        if (context != null && !context.isBlank()) {
            String state = "<system-reminder>\n[Runtime task context]\n" + context + "\n</system-reminder>";
            String previous = messages.stream().filter(AgentLoop::runtimeContext).map(Message::content)
                .reduce((a, b) -> b).orElse("");
            if (!state.equals(previous)) {
                messages.add(Message.user(state));
            }
        }
        // runtimeGuidance 只对本次请求生效，由 requestMessages 临时追加，不写回历史。
    }

    private static boolean runtimeContext(Message message) {
        return ("user".equals(message.role()) || "system".equals(message.role()))
            && message.content().contains("[Runtime task context]");
    }

    private List<Message> requestMessages(List<Message> messages, String guidance) {
        refreshTaskContext(messages, guidance);
        List<String> touched;
        synchronized (touchedPaths) {
            touched = List.copyOf(touchedPaths);
        }
        String rules = scopedRules.apply(touched);
        PhaseHint activeHint = phaseHint;
        boolean hasHint = activeHint != null && activeHint.remaining() > 0 && !activeHint.content().isBlank();
        boolean hasRules = rules != null && !rules.isBlank();
        boolean hasGuidance = guidance != null && !guidance.isBlank();
        if (!hasGuidance && !hasRules && !hasHint) {
            return List.copyOf(messages);
        }
        List<Message> request = new ArrayList<>(messages);
        if (hasHint) {
            request.add(Message.system(activeHint.content()));
        }
        if (hasGuidance) {
            appendGuidance(request, guidance);
        }
        if (hasRules) {
            request.add(Message.system("[路径规则]\n" + rules.strip()));
        }
        return List.copyOf(request);
    }

    /**
     * 临时纠偏默认走尾部 system；以 {@link Message#RUNTIME_CHECKPOINT_MARKER} 开头的行为检查点走临时 user 角色，
     * 仅对本次请求生效（不落盘、不推进真实用户轮次），确保模型在长推理链里确实响应收敛信号。
     */
    private static void appendGuidance(List<Message> request, String guidance) {
        String trimmed = guidance.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith(Message.RUNTIME_CHECKPOINT_MARKER)) {
            request.add(Message.user(trimmed));
            return;
        }
        int marker = trimmed.indexOf("\n\n" + Message.RUNTIME_CHECKPOINT_MARKER);
        if (marker >= 0) {
            String before = trimmed.substring(0, marker).strip();
            String checkpoint = trimmed.substring(marker + 2).strip();
            if (!before.isEmpty()) {
                request.add(Message.system("[Runtime guidance; applies only to this request]\n" + before));
            }
            request.add(Message.user(checkpoint));
            return;
        }
        request.add(Message.system("[Runtime guidance; applies only to this request]\n" + trimmed));
    }

    private void notifyContextShrink() {
        try {
            contextShrinkListener.run();
        } catch (RuntimeException ignored) {
            // 视图失效通知失败不应中断主循环
        }
    }

    /** 估算工具定义、协议头与图片附件带来的非正文固定 token 开销，与 RequestBudget 保持同一口径。 */
    private int fixedOverheadTokens(List<Message> messages) {
        long full = RequestBudget.assess(messages, tools.definitions(), ModelCapacity.active()).estimatedInputTokens();
        long body = ContextCompactor.estimateTokens(messages);
        return (int)Math.max(0L, Math.min(Integer.MAX_VALUE, full - body));
    }

    private void consumePhaseHintTurn() {
        PhaseHint current = phaseHint;
        if (current == null) {
            return;
        }
        int next = current.remaining() - 1;
        phaseHint = next > 0 ? new PhaseHint(current.content(), next, current.candidates()) : null;
    }

    private void clearPhaseHintIfExplored(ToolCall call) {
        PhaseHint current = phaseHint;
        if (current == null || call == null) {
            return;
        }
        // 模型只要已经发起有效代码读取或检索（无论是否命中候选清单），首轮预定位提示就已经完成使命，
        // 立即清退可避免无路径任务里尾部提示连续多轮移位破坏前缀缓存。
        if (PHASE_HINT_EXPLORE_TOOLS.contains(call.name())) {
            clearPhaseHint();
            return;
        }
        if (current.candidates().isEmpty() || call.args() == null) {
            return;
        }
        Object raw = call.args().get("path");
        if (raw == null) {
            return;
        }
        String normalized = normalizeHintPath(raw.toString());
        if (!normalized.isBlank() && current.candidates().contains(normalized)) {
            clearPhaseHint();
        }
    }

    private static String normalizeHintPath(String path) {
        String value = path == null ? "" : path.strip().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }

    private record PhaseHint(String content, int remaining, Set<String> candidates) {
    }

    public AgentLoop withScopedRules(Function<List<String>, String> rules) {
        this.scopedRules = rules == null ? paths -> "" : rules;
        return this;
    }

    private static void safeCheckpoint(Consumer<List<Message>> checkpoint, List<Message> messages) {
        try {
            checkpoint.accept(conversation(messages));
        } catch (RuntimeException ignored) {
            // 记忆落盘失败不应中断本次运行
        }
    }

    /** 重复调用提醒：模板在 {@code prompts/loop/repeat-reminder.md}。 */
    private static String repeatReminder(List<ToolCall> calls, int count) {
        String tools = calls.stream().map(ToolCall::name).distinct().collect(Collectors.joining(", "));
        return PROMPTS.render("loop/repeat-reminder", Map.of("count", String.valueOf(count), "tool", tools)).strip();
    }

    /** 动态任务仍在运行但观察没有变化时，要求模型换成有意义的等待/替代动作。 */
    private static String stuckReminder(List<ToolCall> calls, int count, String unchangedState) {
        String tools = calls.stream().map(ToolCall::name).distinct().collect(Collectors.joining(", "));
        return PROMPTS
            .render("loop/stuck-nudge", Map.of("pattern", "重复观察后台工具：" + tools, "repeatCount", String.valueOf(count),
                "unchangedState", unchangedState, "blockedActions", "- 不要立刻用相同参数高频轮询\n- 不要重新启动同一个后台任务"))
            .strip();
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
     * 按失败分类决定后续动作：断网、超时、连接中途断开、408/429/5xx 都一直重试（约 3 秒一次，
     * 用户可随时取消）；只有 401/403/402、余额或额度耗尽、模型不支持、请求或响应结构非法
     * 这类重试也不会变好的错误才立刻失败。
     *
     * <p>线程被外部中断（服务重启、关停）既不是用户取消也不是模型故障：清掉中断标记后继续重试。
     *
     * <p>唯一的例外是连接级失败：同一端点连续 {@link ModelRetryPolicy#CONNECTION_BREAKER_FAILURES}
     * 次握手失败、EOF 或连接被重置时直接以 {@code MODEL_ERROR} 停下，不让用户干等退避预算耗尽；
     * 对话与进度已经保存，修好网络后可以继续。
     */
    private ModelTurn callModel(List<Message> messages, Consumer<SessionEvent> events, UsageTracker usage,
        RuntimeGuidance runtimeGuidance) {
        int attempt = 0;
        int repeatAborts = 0;
        int overflowRetries = 0;
        int malformedToolRetries = 0;
        String retainedGuidance = null;
        AtomicBoolean reasoningPreviewVisible = new AtomicBoolean();
        while (true) {
            if (cancellation.cancelled()) {
                return new ModelTurn(null, stopForCancellation(messages, events));
            }
            StreamRepeatGuard repeatGuard = new StreamRepeatGuard();
            StreamRepeatGuard reasoningRepeatGuard = new StreamRepeatGuard();
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
                if (cancellation.cancelled()) {
                    return new ModelTurn(null, stopForCancellation(messages, events));
                }
                // 本地控制提示与 policy guidance 都只属于这次采样。模型请求发生瞬时失败时
                // retainedGuidance 会沿重试保留；真正拿到响应后才消费。
                planGate.observeModelRequest();
                if (!planSnapshot().isEmpty()) {
                    planGate.markPlanned();
                }
                String planReminder = planGate.takeReminder();
                if (planReminder != null) {
                    runtimeGuidance.add(planReminder);
                }
                retainedGuidance = RuntimeGuidance.merge(retainedGuidance, runtimeGuidance.render(policyGuidance));
                // 阶段性纠偏属于运行时上下文，只进入当前请求的 system 消息；下一次请求会
                // 重新构造 system，checkpoint/conversation 也从不保存它。
                RequestBudget.closeDanglingToolCalls(messages);
                List<Message> request = requestMessages(messages, retainedGuidance);
                RequestBudget.Assessment admission =
                    RequestBudget.assessRequest(request, tools.definitions(), contextLimit());
                if (!admission.fits()) {
                    // 本地字符预算比发送线松，长对话会在这里才发现超窗。先折掉更早的记录再发，
                    // 不把整轮停成失败。系统提示和工具定义本身都放不下时，折叠也没有用。
                    fitHistoryToInputBudget(messages, events, retainedGuidance);
                    request = requestMessages(messages, retainedGuidance);
                    admission = RequestBudget.assessRequest(request, tools.definitions(), contextLimit());
                }
                if (!admission.fits()
                    && (retainedGuidance != null && !retainedGuidance.isBlank() || phaseHint != null)) {
                    // 临时纠偏与阶段提示都只是辅助上下文，差一点点时先拿掉它们再发，不要为此中断任务。
                    retainedGuidance = null;
                    clearPhaseHint();
                    fitHistoryToInputBudget(messages, events, null);
                    request = requestMessages(messages, null);
                    admission = RequestBudget.assessRequest(request, tools.definitions(), contextLimit());
                }
                if (!admission.fits()) {
                    throw new ExecutionPolicy.Stop(RunFailureCode.CONTEXT_TOO_LARGE,
                        "完整模型请求估算为 " + admission.estimatedInputTokens() + " token，安全输入预算为 " + admission.inputLimit()
                            + "；未发送请求。请缩小输入或拆分任务后继续。");
                }
                // 每次真正发请求前都作废上一段预览：工具调用、策略催办或重试都会开启新一轮生成，
                // 上一轮的字如果留着，会和这次从头输出的内容拼成重复段落。
                if (liveText != null) {
                    events.accept(AssistantPreview.discarded());
                }
                if (reasoningPreviewVisible.getAndSet(false)) {
                    events.accept(AssistantReasoning.discarded());
                }
                usage.start(request, admissionLimit(), tools.definitions());
                ModelResponse response = client.chat(request, tools.definitions(), delta -> {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    // 同一行连续重复到阈值就止损：继续流下去只会把上下文和界面一起刷满。
                    if (repeatGuard.accept(delta) > 0) {
                        throw new StreamRepeatException();
                    }
                    if (liveText == null) {
                        return;
                    }
                    try {
                        liveText.accept(delta);
                    } catch (RuntimeException ignored) {
                        // 界面预览失败不影响这一次模型请求
                    }
                }, delta -> {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    if (reasoningRepeatGuard.accept(delta) > 0) {
                        throw new StreamRepeatException();
                    }
                    if (liveReasoning != null) {
                        try {
                            liveReasoning.accept(delta);
                            reasoningPreviewVisible.set(true);
                        } catch (RuntimeException ignored) {
                            // 推理预览失败不影响这一次模型请求
                        }
                    }
                });
                usage.finish(response);
                observedThroughIndex = messages.size();
                // 一次成功请求说明端点恢复了，下一次故障重新计算退避与预算
                retryBudget.reset();
                connectionFailures = 0;
                runtimeGuidance.clear();
                consumePhaseHintTurn();
                // client 已拿到请求快照；立即还原基础 system，避免当前内存历史也携带一次性提示。
                refreshTaskContext(messages, null);
                return new ModelTurn(response, null);
            } catch (ExecutionPolicy.Stop stop) {
                usage.abort();
                if (RunFailureCode.CONTEXT_TOO_LARGE.is(stop.code())) {
                    return new ModelTurn(null, new Result(stop.getMessage(), stop.code(), conversation(messages)));
                }
                throw stop;
            } catch (StreamRepeatException repeat) {
                usage.abort();
                Thread.interrupted();
                repeatAborts++;
                // 界面上已经出现的重复段落作废：重试会从头重新输出，不能和残留内容拼在一起。
                events.accept(AssistantPreview.discarded());
                if (reasoningPreviewVisible.getAndSet(false)) {
                    events.accept(AssistantReasoning.discarded());
                }
                if (repeatAborts >= MAX_REPEAT_ABORTS) {
                    throw new ExecutionPolicy.Stop(RunFailureCode.MODEL_REPEATED,
                        "模型连续 " + repeatAborts + " 次陷入逐行重复，已中止本轮生成；已有进度已保存。可以继续执行，或把问题拆小后重新发送。");
                }
                runtimeGuidance.add(REPEAT_GUIDANCE);
                events.accept(new RunContinued(1, 1, "模型输出陷入逐行重复，已中止本次生成并重试"));
                continue;
            } catch (RuntimeException e) {
                usage.abort();
                if (e instanceof RequestBudget.BudgetExceeded) {
                    return new ModelTurn(null,
                        new Result(e.getMessage(), RunFailureCode.CONTEXT_TOO_LARGE.name(), conversation(messages)));
                }
                // 客户端抛错时会重新设置中断位；不清掉的话，后续每次请求都会立刻失败。
                Thread.interrupted();
                if (cancellation.cancelled()) {
                    return new ModelTurn(null, stopForCancellation(messages, events));
                }
                if (client.disableRejectedNativeTools(e)) {
                    events.accept(new RunContinued(1, 1, "服务端拒绝原生工具，已自动恢复 Pilot 工具继续当前任务"));
                    continue;
                }
                ModelFailureKind kind = ModelRetryPolicy.classify(e);
                if (kind == ModelFailureKind.CONTEXT_OVERFLOW) {
                    overflowRetries++;
                    int beforeChars = ContextCompactor.totalChars(messages);
                    retainedGuidance = null;
                    fitHistoryToInputBudget(messages, events, null);
                    boolean shrunk = ContextCompactor.totalChars(messages) < beforeChars;
                    if (overflowRetries > 2 || !shrunk && !requestFits(messages, null)) {
                        return new ModelTurn(null, new Result("模型服务端拒绝了超长输入。已尝试收缩历史，仍然放不下。请缩小输入或拆分任务后继续。",
                            RunFailureCode.CONTEXT_TOO_LARGE.name(), conversation(messages)));
                    }
                    events.accept(new RunContinued(overflowRetries, 2, "模型服务端提示上下文超限，已收缩历史并自动重试"));
                    continue;
                }
                if (kind == ModelFailureKind.MALFORMED_TOOL_CALL) {
                    malformedToolRetries++;
                    events.accept(AssistantPreview.discarded());
                    if (reasoningPreviewVisible.getAndSet(false)) {
                        events.accept(AssistantReasoning.discarded());
                    }
                    if (malformedToolRetries > MAX_MALFORMED_TOOL_RETRIES) {
                        throw new ExecutionPolicy.Stop(RunFailureCode.MODEL_ERROR,
                            "模型连续 " + malformedToolRetries + " 次返回非法工具调用参数 JSON，已停止重试；进度和对话已保存，可继续执行。");
                    }
                    runtimeGuidance.add(MALFORMED_TOOL_GUIDANCE);
                    events.accept(
                        new RunContinued(malformedToolRetries, MAX_MALFORMED_TOOL_RETRIES, "工具调用参数 JSON 非法，已提示模型重发"));
                    continue;
                }
                if (kind == ModelFailureKind.INTERRUPTED) {
                    // 用户取消已在上面的分支返回。能走到这里说明是外部中断：服务重启、
                    // 容器关停、线程被别的组件打断。按瞬时故障继续重试，
                    // 不让一次外部中断把整轮任务判死。
                    kind = ModelFailureKind.TRANSIENT;
                }
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                // 永久错误（鉴权、余额、模型不支持、结构非法）重试不会变好，立刻失败
                if (!kind.retryable()) {
                    throw new ExecutionPolicy.Stop(RunFailureCode.MODEL_ERROR, "模型调用失败：" + Texts.oneLine(reason, 300));
                }
                connectionFailures = ModelRetryPolicy.connectionLost(reason) ? connectionFailures + 1 : 0;
                if (connectionFailures >= ModelRetryPolicy.CONNECTION_BREAKER_FAILURES) {
                    throw new ExecutionPolicy.Stop(RunFailureCode.MODEL_ERROR, "同一端点连续 " + connectionFailures
                        + " 次连接级失败（handshake / EOF / connection reset），已停止重试；进度和对话已保存，可继续执行。");
                }
                RetryBudget.Decision decision = retryBudget.afterFailure(reason);
                if (!decision.retry()) {
                    // 预算耗尽或熔断：明确停下并说明可以继续，而不是无声地一直重试
                    throw new ExecutionPolicy.Stop(RunFailureCode.MODEL_ERROR, "模型调用重试已停止：" + decision.reason()
                        + "。已等待 " + Math.max(0, decision.elapsedMs() / 1000) + " 秒，" + "进度与对话已保存；确认模型服务恢复后可继续执行。");
                }
                attempt = decision.attempt();
                events.accept(new RunRetrying(attempt, decision.delayMs(), Texts.oneLine(reason, 300),
                    decision.elapsedMs(), decision.remainingMs(), retryBudget.maxAttempts()));
                // 上一次尝试可能已经推过一段预览：重试会重新输出全文，旧片段必须先作废。
                events.accept(AssistantPreview.discarded());
                if (!pause(decision.delayMs())) {
                    return new ModelTurn(null, stopForCancellation(messages, events));
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

    private Result stopForCancellation(List<Message> messages, Consumer<SessionEvent> events) {
        String reason = cancellation.reason();
        if (reason != null && reason.startsWith(RunFailureCode.BUDGET_EXHAUSTED.name())) {
            return new Result(null, RunFailureCode.BUDGET_EXHAUSTED.name(), conversation(messages));
        }
        return cancel(messages, events);
    }

    private Result cancel(List<Message> messages, Consumer<SessionEvent> events) {
        events.accept(new RunCancelled("运行已被用户取消。"));
        return new Result(null, RunFailureCode.CANCELLED.name(), conversation(messages));
    }

    /** 端点报告了缓存命中（且用量确实来自端点）才认；估出来的缓存量不能当证据。 */
    private void noteUsage(ModelUsage usage) {
        if (usage.exact() && usage.inputTokens() > 0 && usage.cachedInputTokens() > 0) {
            observedCacheHit.set(true);
        }
    }

    /** 真正会拿去发给模型的输入上限。界面和日常压缩都用这一口径，避免本地 40 万字符预算把 10 万的发送线挡住。 */
    int admissionLimit() {
        long wire = ModelCapacity.active().inputBudget();
        long local = (long)(contextLimit() * 0.80);
        return (int)Math.max(1, Math.min(Integer.MAX_VALUE, Math.min(local, wire)));
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
        return calls.stream().map(call -> call.name() + "(" + ToolSupport.argumentSignature(call.args(), "null") + ")")
            .collect(Collectors.joining(";"));
    }

    /** 流式正文陷入逐行复读：只用于在当前请求内部止损，不参与模型错误分类。 */
    private static final class StreamRepeatException extends RuntimeException {
        StreamRepeatException() {
            super("模型输出逐行重复", null, false, false);
        }
    }

    /**
     * 单次采样可见的运行时控制提示。它不进入 messages，因此不会伪装成用户意图，
     * checkpoint 与最终 conversation 也不会把它永久保存下来。
     */
    private static final class RuntimeGuidance {
        private final LinkedHashSet<String> pending = new LinkedHashSet<>();

        private void add(String value) {
            if (value != null && !value.isBlank()) {
                pending.add(value.strip());
            }
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
            if (!hasFirst) {
                return hasSecond ? second.strip() : null;
            }
            if (!hasSecond) {
                return first.strip();
            }
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
            this.abortAt = (int)Math.min(Integer.MAX_VALUE, expanded);
        }

        private boolean canObserveAgain(String nextSignature, List<ToolCall> calls) {
            return awaiting && nextSignature.equals(signature) && dynamicBatch(calls);
        }

        private Decision observe(String currentSignature, List<ToolCall> calls, List<ToolExecutor.Outcome> outcomes) {
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
                    || (ToolNames.COMMAND_LOGS.equals(call.name()) && outcome.result() != null
                        && outcome.result().contains("eof: false"));
                stillAwaiting &= callAwaiting;
                current.append(call.name()).append(':').append(outcome.status()).append(':').append(outcome.result())
                    .append('\n');
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
            return new Decision(remindAt > 0 && unchangedCount == remindAt, unchangedCount >= abortAt, unchangedCount);
        }

        private void reset() {
            signature = null;
            observation = null;
            unchangedCount = 0;
            awaiting = false;
        }

        private static boolean dynamicBatch(List<ToolCall> calls) {
            return !calls.isEmpty() && calls.stream().allMatch(
                call -> ToolNames.COMMAND_STATUS.equals(call.name()) || ToolNames.COMMAND_LOGS.equals(call.name()));
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
        /** 每拿到一次端点用量就回传，用来记录本 run 是否出现过缓存命中。 */
        private final Consumer<ModelUsage> observer;
        private int requests;
        private long inputTokens;
        private long outputTokens;
        private long cachedInputTokens;
        private long pendingInput;
        private int contextTokens;
        private int contextLimit = 1;
        private boolean allExact = true;

        private UsageTracker(Consumer<SessionEvent> events, Consumer<ModelUsage> observer) {
            this.events = events;
            this.observer = observer;
        }

        private void start(List<Message> messages, int limit) {
            start(messages, limit, List.of());
        }

        private void start(List<Message> messages, int limit, List<ToolDefinition> tools) {
            pendingInput = RequestBudget.assessRequest(messages, tools, limit).estimatedInputTokens();
            contextTokens = (int)Math.min(Integer.MAX_VALUE, pendingInput);
            contextLimit = Math.max(1, limit);
            requests++;
            publish(false);
        }

        private void finish(ModelResponse response) {
            ModelUsage reported = response == null ? ModelUsage.unknown() : response.usage();
            if (reported != null && reported.present()) {
                observer.accept(reported);
                inputTokens += reported.inputTokens() > 0 ? reported.inputTokens() : pendingInput;
                outputTokens += reported.outputTokens();
                // 缓存命中只有在端点真的给出了输入用量时才可信；回落到本地估算的那一步
                // 没有缓存观测，计入会让命中率虚高。
                if (reported.exact() && reported.inputTokens() > 0) {
                    cachedInputTokens += reported.cachedInputTokens();
                }
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
            if (pendingInput <= 0) {
                return;
            }
            // 请求可能已经到达端点，但失败响应通常没有 usage；保留已发出的输入估算，
            // 避免界面在失败/重试时先增长又倒退，并明确降为非精确值。
            inputTokens += pendingInput;
            pendingInput = 0;
            allExact = false;
            publish(false);
        }

        private long estimateOutput(ModelResponse response) {
            if (response == null) {
                return 0;
            }
            long total = ContextCompactor.estimateTokens(response.content());
            for (ToolCall call : response.toolCalls() == null ? List.<ToolCall>of() : response.toolCalls()) {
                total += ContextCompactor.estimateTokens(call.name());
                total += ContextCompactor.estimateTokens(String.valueOf(call.args()));
            }
            return total;
        }

        private void publish(boolean exact) {
            long visibleInput = inputTokens + pendingInput;
            events.accept(new RunUsageUpdated(requests, visibleInput, outputTokens, cachedInputTokens, contextTokens,
                contextLimit, exact && pendingInput == 0));
        }
    }

    /** 重试前等待。测试传入立刻返回的实现，避免真的睡几秒。 */
    interface Pause {
        void await(long millis) throws InterruptedException;
    }
}
