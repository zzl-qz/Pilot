package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.RunSteered;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.trace.TraceRecorder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是 Pilot，一个在本地工作区中工作的编码代理。
            你可以使用提供的工具查看文件、搜索文本，并在用户授权后修改文件、执行受限命令。
            所有路径都必须使用工作区内的相对路径，命令在工作区根目录执行。
            先获取事实再回答，不要编造未读取过的文件内容。
            修改文件时优先用 edit_file，并保证 old_string 唯一；文件不存在时用 create_file。
            改完代码后尽量用 run_command 运行相关的构建或测试来验证，并在结论中说明执行了什么命令、结果如何。
            多步任务（预计需要 3 次以上工具调用）先调用 update_plan 列出全部步骤，每完成一步立即更新状态；
            同一时间只保留一个 in_progress，全部做完后把所有步骤置为 completed。
            计划里还有未完成项时不要输出总结或收尾，直接继续做；确实被外部条件阻塞时，说明原因并如实更新计划状态。
            上下文被压缩时，摘要里会带上目标与待办，继续按摘要推进，不要重复已完成的工作。
            完成任务后用简洁的中文给出结论，必要处引用文件路径与行号。
            """;

    /**
     * @param repeatedToolCallLimit 连续相同工具调用达到该次数即中断（含第一次）
     * @param plan                  当前计划快照，收尾时用来判断「任务是否真的做完」；默认无计划
     * @param maxPlanContinues      计划还有未完成项时自动催模型继续的次数上限；0 表示不自动继续
     */
    public record Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                         Supplier<List<PlanItem>> plan, int maxPlanContinues) {
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
            plan = plan == null ? List::of : plan;
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt,
                      Supplier<List<PlanItem>> plan) {
            this(maxSteps, repeatedToolCallLimit, systemPrompt, plan, DEFAULT_MAX_PLAN_CONTINUES);
        }

        public Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt) {
            this(maxSteps, repeatedToolCallLimit, systemPrompt, List::of, DEFAULT_MAX_PLAN_CONTINUES);
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
    private static final int MIN_COMPACTABLE_CHARS = 400;
    /** 达到预算该比例即预压缩一次；超过强制档时允许多压几轮。 */
    /** 计划未完成时默认自动催模型继续的次数上限。 */
    private static final int DEFAULT_MAX_PLAN_CONTINUES = 3;

    private static final double PRE_COMPACT_RATIO = 0.70;
    private static final double FORCE_COMPACT_RATIO = 0.85;
    private static final int MAX_COMPACT_ROUNDS = 3;

    private final ModelClient client;
    private final ToolRegistry tools;
    private final ContextCompactor compactor;
    private final ToolExecutor toolExecutor;
    private final Config config;
    private final Cancellation cancellation;
    private final Supplier<String> pendingInput;
    private final TraceRecorder traceRecorder;
    /** 上次压缩没有实际收益时的上下文大小；涨过这个水位之前不再尝试，避免每步白跑一次摘要。 */
    private int compactBackoffUntil;

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
                (call, result, latencyMs) -> { });
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config, Cancellation cancellation,
                     Supplier<String> pendingInput, TraceRecorder traceRecorder) {
        this.pendingInput = Objects.requireNonNull(pendingInput, "pendingInput must not be null");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
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
        messages.add(Message.user(userInput.strip(), attachments == null ? List.of() : attachments));
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

                ModelResponse response;
                try {
                    response = client.chat(List.copyOf(messages), tools.definitions());
                } catch (RuntimeException e) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    events.accept(new RunFailed("MODEL_ERROR", "模型调用失败：" + ToolSupport.oneLine(reason, 300), true));
                    return new Result(null, "MODEL_ERROR", conversation(messages));
                }

                String content = response.content() == null ? "" : response.content();
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
                    ToolExecutor.Outcome outcome = toolExecutor.execute(call, events);
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

    /** 自动续跑时给模型的提醒：说清还剩什么，并要求它别再用总结收尾。 */
    private static String continuePrompt(List<String> openSteps) {
        var prompt = new StringBuilder("[自动继续] 计划里还有未完成项，请直接继续执行，不要只用总结回复：\n");
        for (String step : openSteps) {
            prompt.append("- ").append(step).append('\n');
        }
        prompt.append("全部做完后用 update_plan 把对应项标记为 completed。");
        return prompt.toString();
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

    private Result cancel(List<Message> messages, Consumer<SessionEvent> events) {
        events.accept(new RunCancelled("运行已被用户取消。"));
        return new Result(null, "CANCELLED", conversation(messages));
    }

    private static int contextLimit() {
        String override = System.getProperty("pilot.context.max-chars");
        if (override != null) {
            try {
                int value = Integer.parseInt(override.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 回落默认预算
            }
        }
        return DEFAULT_CONTEXT_LIMIT;
    }

    /**
     * 上下文预算：优先把较早的历史折叠成交接摘要；摘要不可用时退回「长工具输出换占位符」。
     * 两条路径都不破坏 system / user / assistant / tool_call 的结构。
     */
    private void compact(List<Message> messages, int limit, Consumer<SessionEvent> events) {
        int tokenBudget = Math.max(1, limit / 4);
        int tokens = ContextCompactor.estimateTokens(messages);
        if (tokens < tokenBudget * PRE_COMPACT_RATIO) {
            compactBackoffUntil = 0;
            return;
        }

        // 上一次压缩白跑了一趟模型：在上下文再涨 5% 之前别再试，否则每一步都要多花一次摘要调用
        int current = ContextCompactor.totalChars(messages);
        if (compactBackoffUntil > 0 && current < compactBackoffUntil) {
            if (current > limit) {
                truncateToolOutputs(messages, limit, current);
            }
            return;
        }

        // 70% 预压缩一次；85% 以上允许多压几轮，直到回落到 70% 以下
        int rounds = tokens >= tokenBudget * FORCE_COMPACT_RATIO ? MAX_COMPACT_ROUNDS : 1;
        int targetChars = (int) (tokenBudget * PRE_COMPACT_RATIO) * 4;
        boolean reduced = false;
        for (int round = 0; round < rounds; round++) {
            ContextCompactor.Outcome outcome = compactor.compact(messages, targetChars);
            if (!outcome.compacted() || outcome.charactersAfter() >= outcome.charactersBefore()) {
                break;
            }
            reduced = true;
            events.accept(new ContextCompacted(outcome.coveredMessages(), outcome.charactersBefore(),
                    outcome.charactersAfter(), outcome.summary()));
            if (ContextCompactor.estimateTokens(messages) < tokenBudget * PRE_COMPACT_RATIO) {
                break;
            }
        }

        int total = ContextCompactor.totalChars(messages);
        compactBackoffUntil = reduced ? 0 : (int) (total * 1.05) + 1;
        // 摘要压不动时退回截断，保证上下文不会无限增长
        if (total > limit) {
            truncateToolOutputs(messages, limit, total);
        }
    }

    private static void truncateToolOutputs(List<Message> messages, int limit, int total) {

        record Candidate(int index, int length) {
        }
        var candidates = new ArrayList<Candidate>();
        for (int i = 1; i < messages.size(); i++) {
            Message message = messages.get(i);
            if ("tool".equals(message.role()) && message.content().length() >= MIN_COMPACTABLE_CHARS) {
                candidates.add(new Candidate(i, message.content().length()));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::length).reversed());

        for (Candidate candidate : candidates) {
            if (total <= limit) {
                break;
            }
            Message message = messages.get(candidate.index());
            String placeholder = "[工具输出已省略 " + candidate.length() + " 字符，需要时请重新调用工具]";
            messages.set(candidate.index(), Message.tool(message.toolCallId(), placeholder));
            total -= candidate.length() - placeholder.length();
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
