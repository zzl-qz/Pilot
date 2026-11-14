package com.pilot.core.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.ApprovalDecision;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.hook.HookCommandTool;
import com.pilot.core.hook.Hooks;
import com.pilot.core.hook.Hooks.Result;
import com.pilot.core.loop.Attachment;
import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PermissionResult;
import com.pilot.core.trace.TraceRecorder;
import com.pilot.core.util.Texts;

/**
 * 执行单次工具调用：权限检查 → （必要时）挂起等待审批 → 执行 → 钩子回注 → 上报事件。
 * 审批在这里阻塞 Agent 线程，不轮询；超时由 {@link ApprovalBroker} 以拒绝结束。
 */
public final class ToolExecutor {
    /**
     * @param changedPath 写工具实际改动的文件（展示用相对路径），未改动为 {@code null}
     * @param check       验证类工具产生的一行结果摘要，例如 {@code mvn -q test → exit 0}
     */
    public enum OutcomeStatus {
        SUCCESS, EMPTY, TRANSIENT_FAILURE, PERMANENT_FAILURE, RESULT_UNKNOWN, DENIED, RUNNING, CANCELLED, SKIPPED
    }

    public record Outcome(String toolName, String result, List<String> changedPaths, List<String> checks,
        OutcomeStatus status, List<Attachment> images) {
        public Outcome(String toolName, String result, List<String> changedPaths, List<String> checks,
            OutcomeStatus status) {
            this(toolName, result, changedPaths, checks, status, List.of());
        }

        public Outcome(String toolName, String result, String changedPath, String check) {
            this(toolName, result, singleton(changedPath), singleton(check), classify(result, singleton(check)));
        }

        public Outcome(String toolName, String result, String changedPath, String check, OutcomeStatus status) {
            this(toolName, result, singleton(changedPath), singleton(check), status);
        }

        public Outcome {
            changedPaths = normalized(changedPaths);
            checks = normalized(checks);
            images = images == null ? List.of() : List.copyOf(images);
        }

        /** 兼容只产生单个路径的既有调用方。 */
        public String changedPath() {
            return changedPaths.isEmpty() ? null : changedPaths.getFirst();
        }

        /** 兼容只产生单个检查项的既有调用方。 */
        public String check() {
            return checks.isEmpty() ? null : checks.getFirst();
        }

        public boolean usableEvidence() {
            return status == OutcomeStatus.SUCCESS;
        }
    }

    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final TraceRecorder traceRecorder;
    /** 运行时可替换：AgentLoop 先构造执行器，再按工作区挂上配置好的钩子。 */
    private volatile Hooks hooks;
    private ExecutionBackends.Selection hookExecution;

    public ToolExecutor withHookExecution(ExecutionBackends.Selection execution) {
        this.hookExecution = execution;
        return this;
    }

    /** 工具调用幂等账本；为空表示不记录也不重放。 */
    private volatile ToolCallJournal journal = ToolCallJournal.none();
    private volatile String journalRunId;

    public ToolExecutor(ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals) {
        this(tools, permissionGate, approvals, (call, result, latencyMs) -> {
        });
    }

    public ToolExecutor(ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        TraceRecorder traceRecorder) {
        this(tools, permissionGate, approvals, traceRecorder, Hooks.none());
    }

    public ToolExecutor(ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        TraceRecorder traceRecorder, Hooks hooks) {
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
        this.hooks = hooks == null ? Hooks.none() : hooks;
    }

    public ToolExecutor withHooks(Hooks hooks) {
        this.hooks = hooks == null ? Hooks.none() : hooks;
        return this;
    }

    /** 挂上幂等账本：同一 run 内参数完全相同的写文件调用只真正执行一次。 */
    public ToolExecutor withJournal(String runId, ToolCallJournal journal) {
        this.journalRunId = runId == null || runId.isBlank() ? null : runId;
        this.journal = journal == null ? ToolCallJournal.none() : journal;
        return this;
    }

    public Outcome execute(ToolCall call, Consumer<SessionEvent> events) {
        return execute(call, events, () -> null);
    }

    /**
     * @param beforeStart 真正进入工具前（含审批结束后）的最后检查；非空原因表示跳过此次执行。
     */
    public Outcome execute(ToolCall call, Consumer<SessionEvent> events, Supplier<String> beforeStart) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(beforeStart, "beforeStart must not be null");

        String argsSummary = summarizeArgs(call);
        events.accept(new ToolStarted(call.callId(), call.name(), argsSummary));
        long startedAt = System.nanoTime();

        ToolResult execution;
        AtomicBoolean invoked = new AtomicBoolean();
        Optional<Tool> tool = tools.find(call.name());
        Availability availability = tool.map(ToolRegistry::available).orElse(Availability.ready());
        ToolCallJournal.Recorded replayed = null;
        if (tool.isEmpty()) {
            execution = ToolResult.text("ERROR: 未知工具 " + call.name() + "，可用工具: " + availableNames());
        } else if (!availability.available()) {
            // 运行时没握手成功的能力不重试：把原因和可行的替代路径一次说清
            execution = ToolResult.text("ERROR: 工具 " + call.name() + " 当前不可用：" + availability.reason()
                + "。不要重复调用；如实告诉用户该能力缺失，并改用其他可用工具或说明无法验证的部分。");
        } else {
            replayed = replay(call, tool.get());
            execution = replayed != null
                ? ToolResult.text(replayed.result() + "\n\n[幂等重放] 这个调用在本次运行中已成功执行过，且文件内容仍与当时一致；" + "本次直接复用结果，没有重复写入。")
                : executeTool(call, tool.get(), argsSummary, events, beforeStart, () -> {
                    Result pre = runHooks(Hooks.Event.PRE_TOOL_USE, call, events, beforeStart);
                    if (pre.blocked()) {
                        return ToolResult.text("ERROR: PreToolUse 钩子阻止了 " + call.name() + "：" + pre.output());
                    }
                    Supplier<ToolResult> invoke = () -> {
                        invoked.set(true);
                        return run(tool.get(), call);
                    };
                    // 仅钩子可能引入新的审批等待；普通调用不重复调用自定义权限门。
                    return pre == Result.NONE ? runIfCurrent(beforeStart, invoke)
                        : runWithCurrentPermission(call, tool.get(), beforeStart, invoke);
                });
        }
        String result = execution.text();
        String primaryResult = result;
        // PostToolUse：把格式化/lint 之类每次都要做的结果直接贴在工具结果后面，不用模型再问一次
        if (invoked.get()) {
            Result post = runHooks(Hooks.Event.POST_TOOL_USE, call, events, beforeStart);
            if (!post.output().isBlank()) {
                result = result + "\n\n[PostToolUse]\n" + post.output();
            }
        }

        ArrayList<String> changedPaths =
            new ArrayList<>(replayed != null ? replayed.changedPaths() : execution.changedPaths());
        ArrayList<String> checks = new ArrayList<>(replayed != null ? replayed.checks() : execution.checks());
        String nativeCheck = null;
        if (tool.isPresent() && !blocked(result)) {
            if (succeeded(result) && tool.get() instanceof WritesFiles writes) {
                add(changedPaths, writes.changedPath(call));
            }
            // 失败和超时也要留下验证记录：否则「跑过但挂了」在收尾门和界面上都看不见
            if (replayed == null && tool.get() instanceof ProducesCheck produces) {
                nativeCheck = produces.checkSummary(call, result);
                add(checks, nativeCheck);
            }
        }
        remember(call, replayed, result, changedPaths, checks);

        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        try {
            traceRecorder.record(call, result, latencyMs);
        } catch (RuntimeException ignored) {
            // Trace 写入故障不能阻断代理工具执行。
        }
        // 存档说明只是恢复元数据，不能把空结果变成可用证据。
        OutcomeStatus status = classify(primaryResult, singleton(nativeCheck));
        if (journalRunId != null) {
            try {
                journal.recordObservation(journalRunId, call, result);
                if (Objects.equals(result, journal.observation(journalRunId, call.callId()).orElse(null))) {
                    result += "\n[工具输出存档 run_id=" + journalRunId + " call_id=" + call.callId()
                        + "；使用 read_tool_output 分页读取，禁止重放原操作]";
                } else {
                    result += "\n[工具输出未归档，禁止清理原文]";
                }
            } catch (RuntimeException ignored) {
                result += "\n[工具输出未归档，禁止清理原文]";
            }
        }
        events.accept(new ToolCompleted(call.callId(), Texts.oneLine(result, 120), latencyMs));
        // 结构化子结果里的历史检查不决定外层工具本身成败；外层状态只看正文与本工具自产检查。
        List<Attachment> images = succeeded(result) && !blocked(result) ? execution.images() : List.of();
        return new Outcome(call.name(), result, changedPaths, checks, status, images);
    }

    /** 只对写文件类工具重放：命令重跑是预期行为，不能当成重复调用。 */
    private ToolCallJournal.Recorded replay(ToolCall call, Tool tool) {
        if (journalRunId == null || !(tool instanceof WritesFiles)) {
            return null;
        }
        try {
            return journal.replay(journalRunId, call).filter(ToolCallJournal.Recorded::replayable).orElse(null);
        } catch (RuntimeException e) {
            // 账本读写故障不能阻断正常工具执行；退化成「不幂等」即可
            return null;
        }
    }

    private void remember(ToolCall call, ToolCallJournal.Recorded replayed, String result, List<String> changedPaths,
        List<String> checks) {
        if (journalRunId == null) {
            return;
        }
        if (replayed == null && !changedPaths.isEmpty() && succeeded(result) && !blocked(result)) {
            try {
                journal.record(journalRunId, call, result, changedPaths, checks, true);
            } catch (RuntimeException ignored) {
                // 同上：账本失败不影响本次写入结果
            }
        }
    }

    private static OutcomeStatus classify(String result, List<String> checks) {
        if (result == null || result.isBlank()) {
            return OutcomeStatus.EMPTY;
        }
        String value = result.strip();
        if (ToolResults.isResultUnknown(value)) {
            return OutcomeStatus.RESULT_UNKNOWN;
        }
        if (value.contains("未找到网页结果") || value.contains("没有找到网页结果") || value.contains("无搜索结果")) {
            return OutcomeStatus.EMPTY;
        }
        if (ToolResults.isSkipped(value)) {
            return OutcomeStatus.SKIPPED;
        }
        if (ToolResults.isDenied(value)) {
            return OutcomeStatus.DENIED;
        }
        if (value.contains("状态: RUNNING") || value.contains("状态: STOPPING")) {
            return OutcomeStatus.RUNNING;
        }
        if (value.contains("状态: CANCELLED") || value.contains("状态: TIMED_OUT")) {
            return OutcomeStatus.CANCELLED;
        }
        if (ToolResults.isError(value)) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时")
                || lower.contains("429") || lower.contains("502") || lower.contains("503") || lower.contains("504")
                || lower.contains("连接") || lower.contains("temporar")) {
                return OutcomeStatus.TRANSIENT_FAILURE;
            }
            return OutcomeStatus.PERMANENT_FAILURE;
        }
        if (checks != null && checks.stream().anyMatch(check -> !check.matches(".+ → exit 0"))) {
            return OutcomeStatus.PERMANENT_FAILURE;
        }
        return OutcomeStatus.SUCCESS;
    }

    private static List<String> singleton(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.strip());
    }

    private static List<String> normalized(List<String> values) {
        return values == null ? List.of()
            : values.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().toList();
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value.strip())) {
            values.add(value.strip());
        }
    }

    private static boolean succeeded(String result) {
        return result != null && !ToolResults.isError(result);
    }

    /** 权限拒绝、用户拒绝和控制信号跳过都属于「没执行」，既不算改动也不算验证结果。 */
    private static boolean blocked(String result) {
        return ToolResults.isDenied(result) || ToolResults.isSkipped(result);
    }

    private ToolResult executeTool(ToolCall call, Tool tool, String argsSummary, Consumer<SessionEvent> events,
        Supplier<String> beforeStart, Supplier<ToolResult> action) {
        PermissionResult permission = permissionGate.check(call, tool);
        if (permission.decision() == PermissionDecision.ALLOW && tool.confirm(call.args())) {
            permission = PermissionResult.ask("本次操作需要明确确认，通用 allow 规则不能替代动作授权");
        }
        return switch (permission.decision()) {
            case ALLOW -> runIfCurrent(beforeStart, action);
            case DENY -> ToolResult.text(ToolResults.PERMISSION_DENIED + "：" + permission.reason());
            case ASK -> awaitApproval(call, tool, argsSummary, events, beforeStart, action);
        };
    }

    private ToolResult awaitApproval(ToolCall call, Tool tool, String argsSummary, Consumer<SessionEvent> events,
        Supplier<String> beforeStart, Supplier<ToolResult> action) {
        String detail = tool.approvalDescription(call.args());
        if (detail != null && !detail.isBlank()) {
            argsSummary = detail;
        }
        CompletableFuture<Boolean> decision = approvals.register(call.callId(), tool.name(), argsSummary);
        events.accept(new ApprovalRequired(call.callId(), tool.name(), argsSummary));

        boolean approved;
        try {
            approved = decision.join();
        } catch (CompletionException e) {
            approved = false;
        }

        events.accept(
            new ApprovalResolved(call.callId(), approved ? ApprovalDecision.APPROVED : ApprovalDecision.DENIED));
        return approved ? runWithCurrentPermission(call, tool, beforeStart, action)
            : ToolResult.text(ToolResults.USER_DENIED + "执行 " + tool.name() + "，文件未修改。");
    }

    private ToolResult runWithCurrentPermission(ToolCall call, Tool tool, Supplier<String> guard,
        Supplier<ToolResult> action) {
        return runIfCurrent(guard, () -> {
            PermissionResult current = permissionGate.check(call, tool);
            return current.decision() == PermissionDecision.DENY
                ? ToolResult.text(ToolResults.PERMISSION_DENIED + "：" + current.reason()) : action.get();
        });
    }

    private static ToolResult runIfCurrent(Supplier<String> beforeStart, Supplier<ToolResult> action) {
        String interruption = beforeStart.get();
        return interruption == null || interruption.isBlank() ? action.get()
            : ToolResult.text(ToolResults.skipped(interruption));
    }

    private Result runHooks(Hooks.Event event, ToolCall trigger, Consumer<SessionEvent> events,
        Supplier<String> guard) {
        return hooks.run(event, trigger.name(), command -> {
            var tool = new HookCommandTool(command, hookExecution);
            var call = new ToolCall(trigger.callId() + ".hook." + UUID.randomUUID(), tool.name(),
                Map.of("command", command.spec().command(), "source", command.source().toString(), "event",
                    event.wireName(), "trigger_tool", trigger.name()));
            events.accept(new ToolStarted(call.callId(), call.name(), tool.approvalDescription(call.args())));
            long started = System.nanoTime();
            ToolResult executed =
                executeTool(call, tool, tool.approvalDescription(call.args()), events, guard, () -> run(tool, call));
            events.accept(new ToolCompleted(call.callId(), Texts.oneLine(executed.text(), 120),
                (System.nanoTime() - started) / 1_000_000L));
            return tool.result(executed.text());
        });
    }

    private static ToolResult run(Tool tool, ToolCall call) {
        try {
            Map<String, Object> args = call.args() == null ? Map.of() : call.args();
            ToolResult result = tool.executeResult(args);
            return result == null ? ToolResult.text("") : result;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.text("ERROR: 工具执行失败：" + reason);
        }
    }

    private String availableNames() {
        List<String> names = tools.availableTools().stream().map(Tool::name).toList();
        return names.isEmpty() ? "(无)" : String.join(", ", names);
    }

    private static String summarizeArgs(ToolCall call) {
        Map<String, Object> args = call.args() == null ? Map.of() : call.args();
        for (String key : List.of("path", "pattern")) {
            Object value = args.get(key);
            if (value != null) {
                return Texts.oneLine(key + "=" + value, 120);
            }
        }
        return Texts.oneLine(canonical(args), 120);
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> entry.getKey() + "=" + canonical(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ToolExecutor::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }
}
