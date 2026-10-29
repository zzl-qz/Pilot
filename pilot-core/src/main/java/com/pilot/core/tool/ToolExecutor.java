package com.pilot.core.tool;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.ApprovalDecision;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PermissionResult;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 执行单次工具调用：权限检查 → （必要时）挂起等待审批 → 执行 → 上报事件。
 * 审批在这里阻塞 Agent 线程，不轮询；超时由 {@link ApprovalBroker} 以拒绝结束。
 */
public final class ToolExecutor {
    /**
     * @param changedPath 写工具实际改动的文件（展示用相对路径），未改动为 {@code null}
     * @param check       验证类工具产生的一行结果摘要，例如 {@code mvn -q test → exit 0}
     */
    public enum OutcomeStatus {
        SUCCESS, EMPTY, TRANSIENT_FAILURE, PERMANENT_FAILURE, DENIED, RUNNING, CANCELLED, SKIPPED
    }

    public record Outcome(String toolName, String result, List<String> changedPaths, List<String> checks,
                          OutcomeStatus status) {
        public Outcome(String toolName, String result, String changedPath, String check) {
            this(toolName, result, singleton(changedPath), singleton(check), classify(result, singleton(check)));
        }

        public Outcome(String toolName, String result, String changedPath, String check, OutcomeStatus status) {
            this(toolName, result, singleton(changedPath), singleton(check), status);
        }

        public Outcome {
            changedPaths = normalized(changedPaths);
            checks = normalized(checks);
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
    private final com.pilot.core.trace.TraceRecorder traceRecorder;

    public ToolExecutor(ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals) {
        this(tools, permissionGate, approvals, (call, result, latencyMs) -> { });
    }

    public ToolExecutor(ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
                        com.pilot.core.trace.TraceRecorder traceRecorder) {
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
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
        Optional<Tool> tool = tools.find(call.name());
        if (tool.isEmpty()) {
            execution = ToolResult.text("ERROR: 未知工具 " + call.name() + "，可用工具: " + availableNames());
        } else {
            execution = executeTool(call, tool.get(), argsSummary, events, beforeStart);
        }
        String result = execution.text();

        ArrayList<String> changedPaths = new ArrayList<>(execution.changedPaths());
        ArrayList<String> checks = new ArrayList<>(execution.checks());
        String nativeCheck = null;
        if (tool.isPresent() && !blocked(result)) {
            if (succeeded(result) && tool.get() instanceof WritesFiles writes) {
                add(changedPaths, writes.changedPath(call));
            }
            // 失败和超时也要留下验证记录：否则「跑过但挂了」在收尾门和界面上都看不见
            if (tool.get() instanceof ProducesCheck produces) {
                nativeCheck = produces.checkSummary(call, result);
                add(checks, nativeCheck);
            }
        }

        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        try {
            traceRecorder.record(call, result, latencyMs);
        } catch (RuntimeException ignored) {
            // Trace 写入故障不能阻断代理工具执行。
        }
        events.accept(new ToolCompleted(call.callId(), ToolSupport.oneLine(result, 120), latencyMs));
        // 结构化子结果里的历史检查不决定外层工具本身成败；外层状态只看正文与本工具自产检查。
        return new Outcome(call.name(), result, changedPaths, checks, classify(result, singleton(nativeCheck)));
    }

    private static OutcomeStatus classify(String result, List<String> checks) {
        if (result == null || result.isBlank()) return OutcomeStatus.EMPTY;
        String value = result.strip();
        if (value.contains("未找到网页结果") || value.contains("没有找到网页结果")
                || value.contains("无搜索结果")) return OutcomeStatus.EMPTY;
        if (value.startsWith("SKIPPED:")) return OutcomeStatus.SKIPPED;
        if (value.startsWith("ERROR: 权限拒绝") || value.startsWith("ERROR: 用户拒绝")) {
            return OutcomeStatus.DENIED;
        }
        if (value.contains("状态: RUNNING") || value.contains("状态: STOPPING")) return OutcomeStatus.RUNNING;
        if (value.contains("状态: CANCELLED") || value.contains("状态: TIMED_OUT")) return OutcomeStatus.CANCELLED;
        if (value.startsWith("ERROR")) {
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时")
                    || lower.contains("429") || lower.contains("502") || lower.contains("503")
                    || lower.contains("504") || lower.contains("连接") || lower.contains("temporar")) {
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
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value.strip())) values.add(value.strip());
    }

    private static boolean succeeded(String result) {
        return result != null && !result.startsWith("ERROR");
    }

    /** 权限拒绝、用户拒绝和控制信号跳过都属于「没执行」，既不算改动也不算验证结果。 */
    private static boolean blocked(String result) {
        return result != null
                && (result.startsWith("ERROR: 权限拒绝") || result.startsWith("ERROR: 用户拒绝")
                || result.startsWith("SKIPPED:"));
    }

    private ToolResult executeTool(ToolCall call, Tool tool, String argsSummary, Consumer<SessionEvent> events,
                                   Supplier<String> beforeStart) {
        PermissionResult permission = permissionGate.check(call, tool);
        return switch (permission.decision()) {
            case ALLOW -> runIfCurrent(tool, call, beforeStart);
            case DENY -> ToolResult.text("ERROR: 权限拒绝：" + permission.reason());
            case ASK -> awaitApproval(call, tool, argsSummary, events, beforeStart);
        };
    }

    private ToolResult awaitApproval(ToolCall call, Tool tool, String argsSummary, Consumer<SessionEvent> events,
                                     Supplier<String> beforeStart) {
        CompletableFuture<Boolean> decision = approvals.register(call.callId(), tool.name(), argsSummary);
        events.accept(new ApprovalRequired(call.callId(), tool.name(), argsSummary));

        boolean approved;
        try {
            approved = decision.join();
        } catch (CompletionException e) {
            approved = false;
        }

        events.accept(new ApprovalResolved(call.callId(),
                approved ? ApprovalDecision.APPROVED : ApprovalDecision.DENIED));
        return approved
                ? runIfCurrent(tool, call, beforeStart)
                : ToolResult.text("ERROR: 用户拒绝执行 " + tool.name() + "，文件未修改。");
    }

    private static ToolResult runIfCurrent(Tool tool, ToolCall call, Supplier<String> beforeStart) {
        String interruption = beforeStart.get();
        return interruption == null || interruption.isBlank()
                ? run(tool, call) : ToolResult.text("SKIPPED: " + interruption);
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
        List<String> names = tools.tools().stream().map(Tool::name).toList();
        return names.isEmpty() ? "(无)" : String.join(", ", names);
    }

    private static String summarizeArgs(ToolCall call) {
        Map<String, Object> args = call.args() == null ? Map.of() : call.args();
        for (String key : List.of("path", "pattern")) {
            Object value = args.get(key);
            if (value != null) {
                return ToolSupport.oneLine(key + "=" + value, 120);
            }
        }
        return ToolSupport.oneLine(canonical(args), 120);
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> entry.getKey() + "=" + canonical(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ToolExecutor::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }
}
