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
    public record Outcome(String toolName, String result) {
    }

    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;

    public ToolExecutor(ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals) {
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
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

        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        events.accept(new ToolCompleted(call.callId(), ToolSupport.oneLine(result, 120), latencyMs));
        return new Outcome(call.name(), result);
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
