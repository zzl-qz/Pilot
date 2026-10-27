package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.ContextCompacted;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * @param repeatedToolCallLimit 连续相同工具调用达到该次数即中断（含第一次）
     * @param plan                  当前计划快照，收尾时用来判断「任务是否真的做完」；默认无计划
     * @param maxPlanContinues      计划还有未完成项时自动催模型继续的次数上限；0 表示不自动继续
     * @param contextLimit          上下文预算（按字符估算）；太小会频繁压缩、丢细节
     */
    public record Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                         Supplier<List<PlanItem>> plan, int maxPlanContinues, int contextLimit) {
        public Config {
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps must be >= 1");
            }
            if (repeatedToolCallLimit < 1) {
                throw new IllegalArgumentException("repeatedToolCallLimit must be >= 1");
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

        /** 兼容旧调用：上下文预算用默认值。 */
        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                      Supplier<List<PlanItem>> plan, int maxPlanContinues) {
            this(maxSteps, repeatedToolCallLimit, systemPrompt, plan, maxPlanContinues, DEFAULT_CONTEXT_LIMIT);
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                      Supplier<List<PlanItem>> plan) {
            this(maxSteps, repeatedToolCallLimit, systemPrompt, plan, DEFAULT_MAX_PLAN_CONTINUES,
                    DEFAULT_CONTEXT_LIMIT);
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt) {
            this(maxSteps, repeatedToolCallLimit, systemPrompt, List::of, DEFAULT_MAX_PLAN_CONTINUES,
                    DEFAULT_CONTEXT_LIMIT);
        }

        /**
         * 默认步数只作为保险丝：真正约束模型的是上下文预算（{@code compact}）与
         * 重复工具调用检测，触顶后对话历史仍会保留，可以继续下一轮。
         */
        public static Config defaults() {
            return new Config(120, 3, DEFAULT_SYSTEM_PROMPT);
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
    /** 旧工具输出换占位符的起点（预算占比）。 */
    private static final double PRUNE_RATIO = 0.55;
    /** 窗口内超长工具输出首尾截断的起点。 */
    private static final double TRUNCATE_RATIO = 0.70;
    /** 摘要也压不下去时，超过这个水位就硬裁剪。 */
    private static final double DROP_RATIO = 0.97;
    /** 计划未完成时默认自动催模型继续的次数上限。 */
    private static final int DEFAULT_MAX_PLAN_CONTINUES = 3;

    /**
     * 达到预算该比例先压一次；超过强制档时允许多压几轮。
     *
     * <p>0.80 是权衡后的值：0.70 会让长任务在八万字符出头就压，压完只落到七成，
     * 再跑二十来步又越线，于是出现「连着压两次」。压得晚一点、压得狠一点（摘要目标一半），
     * 单次任务的总压缩次数明显更少。
     */
    private static final double PRE_COMPACT_RATIO = 0.80;
    private static final double FORCE_COMPACT_RATIO = 0.90;
    private static final int MAX_COMPACT_ROUNDS = 3;
    /** 断网或模型临时失败：最多再试 3 次，间隔 2 秒起、翻倍、不超过 30 秒。 */
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private static final long RETRY_INITIAL_MS = 2_000L;
    private static final long RETRY_MAX_MS = 30_000L;
    /** 等待重试时拆成小段，取消能在这段时间内停下来。 */
    private static final long RETRY_SLICE_MS = 250L;
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d+)");

    /** 收尾门：改了代码却没有验证时催几次；自审只做一次。 */
    private static final int MAX_VERIFY_PROMPTS = 2;
    private static final int MAX_REVIEW_PROMPTS = 1;
    /** 只改这些后缀的文档不算「改了代码」，不强制跑构建。 */
    private static final Set<String> DOC_SUFFIXES = Set.of(".md", ".txt", ".rst", ".adoc");
    /** 目标像「调研 / 解释 / 定位」时，只读任务也要核对覆盖范围，不能读两个文件就下结论。 */
    private static final List<String> RESEARCH_HINTS = List.of(
            "分析", "梳理", "说明", "解释", "为什么", "怎么实现", "如何实现", "调用链", "流程",
            "审查", "评估", "调研", "看一下", "读一下", "哪些", "对比", "定位");
    private static final List<String> RESEARCH_HINTS_EN = List.of(
            "explain", "analyze", "analyse", "review", "how does", "why", "walk through");
    /** 从验证摘要里读退出码，例如 {@code mvn -q test → exit 0}。 */
    private static final Pattern EXIT_CODE = Pattern.compile("exit (-?\\d+)");

    private final ModelClient client;
    private final ToolRegistry tools;
    private final ContextCompactor compactor;
    private final ToolExecutor toolExecutor;
    private final Config config;
    private final Cancellation cancellation;
    private final Supplier<String> pendingInput;
    private final TraceRecorder traceRecorder;
    private final Pause retryPause;
    /** 正在生成的文字，只给界面看。为空表示不推预览。 */
    private final Consumer<String> liveText;
    /** 上次压缩没有实际收益时的上下文大小；涨过这个水位之前不再尝试，避免每步白跑一次摘要。 */
    private int compactBackoffUntil;
    /** 本轮原始任务，收尾自审时回灌给模型对照。 */
    private String goalText;
    /** 已经催过几次验证 / 自审，用来给收尾门刹车。 */
    private int verifyPrompts;
    private int reviewPrompts;
    /** 本轮工具调用次数，自审提示里用来说明「实际做了多少」。 */
    private int toolCalls;
    /** 本轮碰过的路径，只读任务自审时告诉模型「已经看过哪些」。 */
    private final Set<String> inspected = new LinkedHashSet<>();

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
     * @param pendingInput 每轮采样前拉取一次运行中追加的用户输入；返回 null 表示没有
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
        this.compactor = new ContextCompactor(client);
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
        this.goalText = userInput.strip();
        messages.add(Message.user(goalText, attachments == null ? List.of() : attachments));
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
                compact(messages, contextLimit(), events);

                // 运行中追加的指令：不新开 run，直接进下一轮
                String steered = pendingInput.get();
                if (steered != null && !steered.isBlank()) {
                    messages.add(Message.user(steered.strip()));
                    events.accept(new RunSteered(steered));
                }

                ModelTurn turn = callModel(messages, events);
                if (turn.stop() != null) {
                    return turn.stop();
                }
                ModelResponse response = turn.response();

                String content = response.content() == null ? "" : response.content();
                // 请求成功后只落一条完整回复。预览过程中的片段不进历史，重试也不会粘成两段。
                if (!content.isBlank()) {
                    events.accept(new AssistantDelta(content));
                }
                List<ToolCall> calls = response.toolCalls() == null ? List.of() : response.toolCalls();

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
                    Nudge nudge = completionNudge(changedFiles, checks, plan, openSteps);
                    if (nudge != null) {
                        messages.add(Message.user(nudge.prompt()));
                        events.accept(new RunContinued(nudge.attempt(), nudge.max(), nudge.reason()));
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
                if (repeated >= config.repeatedToolCallLimit()) {
                    String detail = "模型连续 " + repeated + " 次请求相同的工具调用，已中断以避免死循环。";
                    events.accept(new RunFailed("REPEATED_TOOL_CALL", detail, false));
                    return new Result(null, "REPEATED_TOOL_CALL", conversation(messages));
                }

                for (ToolCall call : calls) {
                    if (cancellation.cancelled()) {
                        return cancel(messages, events);
                    }
                    rememberTarget(call);
                    ToolExecutor.Outcome outcome = toolExecutor.execute(call, events);
                    toolCalls++;
                    if (outcome.changedPath() != null) {
                        changedFiles.add(outcome.changedPath());
                    }
                    if (outcome.check() != null) {
                        checks.add(outcome.check());
                    }
                    messages.add(Message.tool(call.callId(), outcome.result()));
                }
                // 每步落盘，进程被打断时最多丢当前这一步
                safeCheckpoint(checkpoint, messages);
            }

            events.accept(new RunFailed("MAX_STEPS",
                    "已达到本轮步数上限 " + config.maxSteps() + "，历史已保留，可以继续执行。", true));
            return new Result(null, "MAX_STEPS", conversation(messages));
        } finally {
            safeCheckpoint(checkpoint, messages);
        }
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

    /**
     * @param attempt 第几次提醒
     * @param max     这类提醒最多几次
     */
    private record Nudge(String prompt, String reason, int attempt, int max) {
    }

    /**
     * 收尾前的两道门：
     * <ol>
     *   <li>验证：改了代码，却没有任何退出码为 0 的构建 / 测试记录；</li>
     *   <li>自审：有文件改动，让模型对照原始目标和计划再核对一遍有没有缩水。</li>
     * </ol>
     * 两道门都有次数上限，用完就放行；{@code -Dpilot.completion.checks=false} 可整体关闭。
     *
     * @return {@code null} 表示可以收尾
     */
    private Nudge completionNudge(Set<String> changedFiles, List<String> checks,
                                  List<PlanItem> plan, List<String> openSteps) {
        if (!completionChecksEnabled()) {
            return null;
        }
        boolean changed = !changedFiles.isEmpty();
        // 只读任务没有改动，但「目标像调研、工具却只用了两三次」同样要核对覆盖范围
        boolean researching = !changed && toolCalls >= 2 && looksLikeResearch(goalText);
        if (!changed && !researching) {
            return null;
        }
        if (changed && touchesCode(changedFiles) && !hasPassingCheck(checks)
                && verifyPrompts < MAX_VERIFY_PROMPTS) {
            verifyPrompts++;
            String prompt = PROMPTS.render("completion/verify", Map.of(
                    "changedFiles", bullets(changedFiles),
                    "checks", checks.isEmpty() ? "（本轮没有跑过构建或测试）" : bullets(checks))).strip();
            return new Nudge(prompt, "改动未验证，已要求先跑构建或测试", verifyPrompts, MAX_VERIFY_PROMPTS);
        }
        if (reviewPrompts < MAX_REVIEW_PROMPTS) {
            reviewPrompts++;
            String goal = goalText == null ? "（续跑，无原始输入）" : ToolSupport.oneLine(goalText, 500);
            String prompt;
            if (changed) {
                int done = (int) plan.stream().filter(PlanItem::done).count();
                prompt = PROMPTS.render("completion/review", Map.of(
                        "goal", goal,
                        "toolCalls", String.valueOf(toolCalls),
                        "changedFiles", bullets(changedFiles),
                        "planProgress", plan.isEmpty()
                                ? "（本轮没有维护计划）"
                                : done + "/" + plan.size() + " 已完成",
                        "openSteps", openSteps.isEmpty() ? "（无）" : bullets(openSteps),
                        "checks", checks.isEmpty() ? "（未执行验证）" : bullets(checks)));
            } else {
                prompt = PROMPTS.render("completion/review-readonly", Map.of(
                        "goal", goal,
                        "toolCalls", String.valueOf(toolCalls),
                        "inspected", inspected.isEmpty() ? "（未记录具体路径）" : bullets(inspected)));
            }
            return new Nudge(prompt.strip(), "收尾前自审：核对目标与结论是否真的覆盖完整",
                    reviewPrompts, MAX_REVIEW_PROMPTS);
        }
        return null;
    }

    /** 记下这次调用碰过的路径，只读任务自审时说明「已经看过哪些」。 */
    private void rememberTarget(ToolCall call) {
        Object path = call.args() == null ? null : call.args().get("path");
        if (path == null) {
            return;
        }
        String text = String.valueOf(path).strip();
        if (!text.isEmpty()) {
            inspected.add(text);
        }
    }

    private static boolean looksLikeResearch(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        String lower = goal.toLowerCase(Locale.ROOT);
        return RESEARCH_HINTS.stream().anyMatch(goal::contains)
                || RESEARCH_HINTS_EN.stream().anyMatch(lower::contains);
    }

    private static String bullets(Collection<String> values) {
        return values.stream().map(value -> "- " + value).collect(Collectors.joining("\n"));
    }

    /** 改动里是否包含代码文件；纯文档改动不强制跑构建。 */
    private static boolean touchesCode(Collection<String> changedFiles) {
        return changedFiles.stream().anyMatch(path -> {
            String lower = path.toLowerCase(Locale.ROOT);
            return DOC_SUFFIXES.stream().noneMatch(lower::endsWith);
        });
    }

    private static boolean hasPassingCheck(List<String> checks) {
        for (String check : checks) {
            Matcher matcher = EXIT_CODE.matcher(check);
            if (matcher.find() && "0".equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean completionChecksEnabled() {
        String flag = System.getProperty("pilot.completion.checks");
        return flag == null || flag.isBlank() || !"false".equalsIgnoreCase(flag.strip());
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
                if (!isTransientModelFailure(e)) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    events.accept(new RunFailed("MODEL_ERROR",
                            "模型调用失败：" + ToolSupport.oneLine(reason, 300), true));
                    return new ModelTurn(null, new Result(null, "MODEL_ERROR", conversation(messages)));
                }
                attempt++;
                if (attempt > MAX_TRANSIENT_RETRIES) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    events.accept(new RunFailed("MODEL_ERROR",
                            "模型暂时不可用，已重试 " + MAX_TRANSIENT_RETRIES + " 次："
                                    + ToolSupport.oneLine(reason, 300), true));
                    return new ModelTurn(null, new Result(null, "MODEL_ERROR", conversation(messages)));
                }
                long delayMs = retryDelayMs(attempt);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                events.accept(new RunRetrying(attempt, delayMs, ToolSupport.oneLine(reason, 300)));
                if (!pause(delayMs)) {
                    return new ModelTurn(null, cancel(messages, events));
                }
            }
        }
    }

    static long retryDelayMs(int attempt) {
        int shift = Math.min(Math.max(attempt, 1) - 1, 4);
        long delay = RETRY_INITIAL_MS << shift;
        return Math.min(delay, RETRY_MAX_MS);
    }

    static boolean isTransientModelFailure(RuntimeException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("被中断")) {
            return false;
        }
        if (message.contains("模型请求失败")) {
            return true;
        }
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) {
            int code = Integer.parseInt(status.group(1));
            return code == 408 || code == 429 || code >= 500;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
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

    /**
     * 上下文预算，按代价从低到高分四层，前两层与最后一层都不花模型调用：
     * <ol>
     *   <li>旧工具输出换占位符（45% 起）</li>
     *   <li>窗口内超长工具输出首尾截断（60% 起）</li>
     *   <li>折叠成交接摘要（70% 起，85% 以上允许多压几轮，压不动就退避）</li>
     *   <li>硬裁剪最旧的组（95% 起）</li>
     * </ol>
     */
    private void compact(List<Message> messages, int limit, Consumer<SessionEvent> events) {
        // 字符预算与 token 预算同值：英文按 4 字符 1 token 远远够用，中文接近 1 字符 1 token，
        // 取两者里更满的那个，避免中文会话被「字符还不多」骗过去，一路涨到超窗。
        int charBudget = Math.max(1, limit);
        int tokenBudget = Math.max(1, limit);
        double usage = usage(messages, charBudget, tokenBudget);

        // 第一、二层不花模型调用，只是把「已经读过的内容」压缩成占位符与头尾
        if (usage >= PRUNE_RATIO) {
            ContextCompactor.pruneToolOutputs(messages, limit);
        }
        if (usage >= TRUNCATE_RATIO) {
            ContextCompactor.truncateLongToolOutputs(messages);
        }

        usage = usage(messages, charBudget, tokenBudget);
        if (usage < PRE_COMPACT_RATIO) {
            compactBackoffUntil = 0;
            dropIfOverBudget(messages, limit);
            return;
        }

        // 上一次摘要白跑了一趟模型：上下文再涨 5% 之前别再试
        int current = ContextCompactor.totalChars(messages);
        if (compactBackoffUntil > 0 && current < compactBackoffUntil) {
            dropIfOverBudget(messages, limit);
            return;
        }

        int rounds = usage >= FORCE_COMPACT_RATIO ? MAX_COMPACT_ROUNDS : 1;
        int targetChars = (int) (charBudget * PRE_COMPACT_RATIO);
        int targetTokens = (int) (tokenBudget * PRE_COMPACT_RATIO);
        boolean reduced = false;
        for (int round = 0; round < rounds; round++) {
            ContextCompactor.Outcome outcome = compactor.compact(messages, targetChars, targetTokens);
            if (!outcome.compacted()) {
                break;
            }
            reduced = true;
            events.accept(new ContextCompacted(outcome.coveredMessages(), outcome.charactersBefore(),
                    outcome.charactersAfter(), outcome.summary()));
            if (usage(messages, charBudget, tokenBudget) < PRE_COMPACT_RATIO) {
                break;
            }
        }

        int total = ContextCompactor.totalChars(messages);
        compactBackoffUntil = reduced ? 0 : (int) (total * 1.05) + 1;
        dropIfOverBudget(messages, limit);
    }

    /** 当前占用 / 预算，取字符与 token 两个口径里更满的那个。 */
    private static double usage(List<Message> messages, int charBudget, int tokenBudget) {
        double byChars = ContextCompactor.totalChars(messages) / (double) charBudget;
        double byTokens = ContextCompactor.estimateTokens(messages) / (double) tokenBudget;
        return Math.max(byChars, byTokens);
    }

    private static void dropIfOverBudget(List<Message> messages, int limit) {
        if (ContextCompactor.totalChars(messages) > limit * DROP_RATIO) {
            ContextCompactor.dropOldestGroups(messages, limit);
        }
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
