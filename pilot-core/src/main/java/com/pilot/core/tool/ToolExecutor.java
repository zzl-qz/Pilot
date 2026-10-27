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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
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
    public record Outcome(String toolName, String result, String changedPath, String check) {
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
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(events, "events must not be null");

        String argsSummary = summarizeArgs(call);
        events.accept(new ToolStarted(call.callId(), call.name(), argsSummary));
        long startedAt = System.nanoTime();

        String result;
        Optional<Tool> tool = tools.find(call.name());
        if (tool.isEmpty()) {
            result = "ERROR: 未知工具 " + call.name() + "，可用工具: " + availableNames();
        } else {
            result = executeTool(call, tool.get(), argsSummary, events);
        }

        String changedPath = null;
        String check = null;
        if (tool.isPresent() && !blocked(result)) {
            if (succeeded(result) && tool.get() instanceof WritesFiles writes) {
                changedPath = writes.changedPath(call);
            }
            // 失败和超时也要留下验证记录：否则「跑过但挂了」在收尾门和界面上都看不见
            if (tool.get() instanceof ProducesCheck produces) {
                check = produces.checkSummary(call, result);
            }
        }

        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        try {
            traceRecorder.record(call, result, latencyMs);
        } catch (RuntimeException ignored) {
            // Trace 写入故障不能阻断代理工具执行。
        }
        events.accept(new ToolCompleted(call.callId(), ToolSupport.oneLine(result, 120), latencyMs));
        return new Outcome(call.name(), result, changedPath, check);
    }

    private static boolean succeeded(String result) {
        return result != null && !result.startsWith("ERROR");
    }

    /** 权限拒绝 / 用户拒绝属于「没执行」，既不算改动也不算验证结果。 */
    private static boolean blocked(String result) {
        return result != null
                && (result.startsWith("ERROR: 权限拒绝") || result.startsWith("ERROR: 用户拒绝"));
    }

    private String executeTool(ToolCall call, Tool tool, String argsSummary, Consumer<SessionEvent> events) {
        PermissionResult permission = permissionGate.check(call, tool);
        return switch (permission.decision()) {
            case ALLOW -> run(tool, call);
            case DENY -> "ERROR: 权限拒绝：" + permission.reason();
            case ASK -> awaitApproval(call, tool, argsSummary, events);
        };
    }

    private String awaitApproval(ToolCall call, Tool tool, String argsSummary, Consumer<SessionEvent> events) {
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
                ? run(tool, call)
                : "ERROR: 用户拒绝执行 " + tool.name() + "，文件未修改。";
    }

    private static String run(Tool tool, ToolCall call) {
        try {
            Map<String, Object> args = call.args() == null ? Map.of() : call.args();
            String result = tool.execute(args);
            return result == null ? "" : result;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "ERROR: 工具执行失败：" + reason;
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
