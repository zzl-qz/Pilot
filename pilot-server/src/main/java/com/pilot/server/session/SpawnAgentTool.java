package com.pilot.server.session;

import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code spawn_agent}：把一段「读得多、结论短」的探索派给子代理。
 *
 * <p>子代理拥有独立的上下文，只把最终结论回传给父代理，主上下文不会被子代理读过的文件撑爆。
 * 第一版是同步执行（父代理等待），因此不存在并发写同一个工作区的问题；
 * 子代理的工具集不包含 spawn_agent，避免递归派生。
 */
public final class SpawnAgentTool implements Tool {
    private static final int MAX_SUMMARY_CHARS = 4_000;

    private final ModelClient client;
    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentLoop.Config config;
    private final Supplier<List<Message>> history;
    private final Consumer<SessionEvent> events;
    private final BooleanSupplier cancelled;

    public SpawnAgentTool(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                          ApprovalBroker approvals, AgentLoop.Config config,
                          Supplier<List<Message>> history, Consumer<SessionEvent> events,
                          BooleanSupplier cancelled) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
    }

    @Override
    public String name() {
        return "spawn_agent";
    }

    @Override
    public String description() {
        return """
                派一个子代理去做一件边界清晰的子任务（例如「盘点哪些文件调用了 X」「只读审查某个模块」）。
                子代理有自己的上下文，只把最终结论返回给你，中间读过的文件不会占用你的上下文。
                适合信息量大但结论很短的探索；需要连续改文件的实现类工作请自己做，不要派出去。
                """;
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of(
                                "type", "string",
                                "description", "子任务的完整描述，包含目标、范围与期望产出"),
                        "fork_turns", Map.of(
                                "type", "string",
                                "enum", List.of("none", "all"),
                                "description", "none = 不带父对话（默认，推荐）；all = 带上当前对话记忆")),
                "required", List.of("task"),
                "additionalProperties", false);
    }

    /** 子代理可能写文件，按写操作对待，走当前权限档位的审批。 */
    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String task = args == null ? "" : text(args.get("task"));
        if (task.isBlank()) {
            return "ERROR: task 不能为空";
        }
        String fork = args == null ? "" : text(args.get("fork_turns"));
        boolean inherit = "all".equals(fork);

        String agentId = "agent_" + UUID.randomUUID().toString().substring(0, 8);
        events.accept(new SubagentStarted(agentId, task));
        try {
            var loop = new AgentLoop(client, tools, permissionGate, approvals, config, cancelled::getAsBoolean);
            List<Message> context = inherit ? history.get() : List.of();
            String prompt = "子任务（由父代理派发）：" + task
                    + "\n只完成这件事，不要在结论里复述过程，给出可执行的结论、涉及的文件与行号。";
            AgentLoop.Result result = loop.run(prompt, context, events);
            if (result.failed()) {
                String reason = "子代理未完成：" + result.failureCode();
                events.accept(new SubagentFinished(agentId, reason, false));
                return "ERROR: " + reason;
            }
            String summary = result.finalText() == null ? "" : result.finalText().strip();
            if (summary.length() > MAX_SUMMARY_CHARS) {
                summary = summary.substring(0, MAX_SUMMARY_CHARS) + "…";
            }
            events.accept(new SubagentFinished(agentId, summary, true));
            return summary.isBlank() ? "子代理完成，但没有返回文本结论。" : summary;
        } catch (RuntimeException e) {
            String reason = "子代理异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            events.accept(new SubagentFinished(agentId, reason, false));
            return "ERROR: " + reason;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
