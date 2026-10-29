package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.ExecutionPolicy;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    /** 子任务只做边界清晰的工作；父代理的 reviewer/finalization 额度不能被它吃掉。 */
    private static final int MAX_SUBAGENT_STEPS = 8;
    /** 一个父 run 最多派生的子任务数；续跑会创建新 run，但累计 Goal 预算不会清零。 */
    private static final int MAX_SPAWNS_PER_RUN = 4;
    /** 所有子任务共享的模型请求池，不能靠不断更换 task 文案绕过单子任务上限。 */
    private static final int MAX_SUBAGENT_REQUESTS_PER_RUN = 24;
    private static final Set<String> FORBIDDEN_TOOLS = Set.of(
            "spawn_agent", "dispatch_agents", "promote_goal", "create_goal", "update_goal",
            "update_plan", "stop_command");
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    private final ModelClient client;
    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentLoop.Config config;
    private final Supplier<List<Message>> history;
    private final Consumer<SessionEvent> events;
    private final BooleanSupplier cancelled;
    private final UsageSink usageSink;
    private final AtomicInteger runSpawns = new AtomicInteger();
    private final AtomicInteger runRequests = new AtomicInteger();

    /** 把子请求计入父任务累计事故熔断；Goal 关闭时使用 no-op，run 级池仍然生效。 */
    interface UsageSink {
        void beforeRequest();

        void afterRequest(long activeMillis, ModelUsage usage);

        static UsageSink none() {
            return new UsageSink() {
                @Override public void beforeRequest() { }
                @Override public void afterRequest(long activeMillis, ModelUsage usage) { }
            };
        }
    }

    public SpawnAgentTool(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                          ApprovalBroker approvals, AgentLoop.Config config,
                          Supplier<List<Message>> history, Consumer<SessionEvent> events,
                          BooleanSupplier cancelled) {
        this(client, tools, permissionGate, approvals, config, history, events, cancelled, UsageSink.none());
    }

    SpawnAgentTool(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                   ApprovalBroker approvals, AgentLoop.Config config,
                   Supplier<List<Message>> history, Consumer<SessionEvent> events,
                   BooleanSupplier cancelled, UsageSink usageSink) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tools = isolatedTools(Objects.requireNonNull(tools, "tools must not be null"));
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.config = subagentConfig(Objects.requireNonNull(config, "config must not be null"));
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
        this.usageSink = Objects.requireNonNull(usageSink, "usageSink must not be null");
    }

    @Override
    public String name() {
        return "spawn_agent";
    }

    @Override
    public String description() {
        return promptDescription();
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
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String task = args == null ? "" : text(args.get("task"));
        if (task.isBlank()) {
            return ToolResult.text("ERROR: task 不能为空");
        }
        String fork = args == null ? "" : text(args.get("fork_turns"));
        boolean inherit = "all".equals(fork);

        int spawn = runSpawns.incrementAndGet();
        if (spawn > MAX_SPAWNS_PER_RUN) {
            return ToolResult.text("ERROR: 本轮子代理派生次数已达到 " + MAX_SPAWNS_PER_RUN
                    + " 次；请先合并已有结果，由父代理继续执行或阶段交付。");
        }

        String agentId = "agent_" + UUID.randomUUID().toString().substring(0, 8);
        events.accept(new SubagentStarted(agentId, task));
        ArtifactCollector artifacts = new ArtifactCollector();
        AtomicReference<RunCompleted> completion = new AtomicReference<>();
        try {
            AgentLoop loop = new AgentLoop(boundedClient(), tools, permissionGate, approvals, config,
                    cancelled::getAsBoolean)
                    .withExecutionPolicy(artifacts);
            List<Message> context = inherit ? history.get() : List.of();
            String prompt = PROMPTS.render("subagent/task", Map.of("task", task)).strip();
            // 子代理有独立累计器；子 run 的终止事件绝不能流入父 sink，否则会提前完成/中断父任务。
            AgentLoop.Result result = loop.run(prompt, context,
                    event -> forwardChildEvent(event, completion));
            if (result.failed()) {
                String reason = "子代理未完成：" + result.failureCode();
                String report = "ERROR: " + reason + resultManifest(artifacts, completion.get());
                events.accept(new SubagentFinished(agentId, report, false));
                return new ToolResult(report, artifacts.changedPaths(), artifacts.checks());
            }
            String summary = result.finalText() == null ? "" : result.finalText().strip();
            if (summary.length() > MAX_SUMMARY_CHARS) {
                summary = summary.substring(0, MAX_SUMMARY_CHARS) + "…";
            }
            summary = summary + resultManifest(artifacts, completion.get());
            events.accept(new SubagentFinished(agentId, summary, true));
            String report = summary.isBlank() ? "子代理完成，但没有返回文本结论。" : summary;
            return new ToolResult(report, artifacts.changedPaths(), artifacts.checks());
        } catch (RuntimeException e) {
            String reason = "子代理异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            String report = "ERROR: " + reason + resultManifest(artifacts, completion.get());
            events.accept(new SubagentFinished(agentId, report, false));
            return new ToolResult(report, artifacts.changedPaths(), artifacts.checks());
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /** 将重试与收尾也计入独立请求保险丝；正常路径是 8 个工作请求 + 1 个阶段收尾。 */
    private ModelClient boundedClient() {
        int maxRequests = MAX_SUBAGENT_STEPS + 1;
        AtomicInteger requests = new AtomicInteger();
        return new ModelClient() {
            @Override public ModelResponse chat(List<Message> messages, List<ToolDefinition> definitions) {
                return chat(messages, definitions, null);
            }

            @Override public ModelResponse chat(List<Message> messages, List<ToolDefinition> definitions,
                                                Consumer<String> textDelta) {
                claimRequest();
                long started = System.nanoTime();
                ModelResponse response = null;
                try {
                    response = client.chat(messages, definitions, textDelta);
                    return response;
                } finally {
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    usageSink.afterRequest(elapsed,
                            response == null ? ModelUsage.unknown() : response.usage());
                }
            }

            private void claimRequest() {
                if (requests.incrementAndGet() > maxRequests) {
                    throw new ExecutionPolicy.Stop("MAX_STEPS",
                            "子代理已达到独立模型请求上限 " + maxRequests + "，返回已有证据给父代理收尾。");
                }
                int total = runRequests.incrementAndGet();
                if (total > MAX_SUBAGENT_REQUESTS_PER_RUN) {
                    throw new ExecutionPolicy.Stop("MAX_STEPS",
                            "本轮所有子代理累计模型请求已达到 " + MAX_SUBAGENT_REQUESTS_PER_RUN
                                    + "，请由父代理合并已有证据并收尾。");
                }
                usageSink.beforeRequest();
            }
        };
    }

    /** 在父工具注册表继续变化前就冻结一份能力快照，并移除所有派生/目标控制工具。 */
    private static ToolRegistry isolatedTools(ToolRegistry source) {
        ToolRegistry isolated = new ToolRegistry();
        source.tools().stream()
                .filter(tool -> !FORBIDDEN_TOOLS.contains(tool.name()))
                .forEach(isolated::register);
        return isolated;
    }

    /** 子代理不继承父计划，也不共享父步数上限；最多八次工作请求，AgentLoop 仍保留一次收尾。 */
    private static AgentLoop.Config subagentConfig(AgentLoop.Config parent) {
        int steps = Math.min(parent.maxSteps(), MAX_SUBAGENT_STEPS);
        return new AgentLoop.Config(steps, parent.repeatRemindAt(), parent.repeatAbortAt(),
                parent.systemPrompt() + "\n\n[子代理隔离] 只完成派发的子任务。你不能创建 Goal、派生其他代理或改写父任务状态。",
                List::of, 0, parent.contextLimit(), parent.completionChecks());
    }

    private void forwardChildEvent(SessionEvent event, AtomicReference<RunCompleted> completion) {
        if (event instanceof RunCompleted completed) {
            completion.set(completed);
            return;
        }
        // 子 run 的 assistant/run/plan/usage 事件没有 agentId，转发会污染父时间线和任务状态。
        // 工具与审批事件仍需透传，否则用户看不到执行进度，也无法批准子代理操作。
        switch (event.type()) {
            case TOOL_STARTED, TOOL_COMPLETED, APPROVAL_REQUIRED, APPROVAL_RESOLVED -> events.accept(event);
            default -> { }
        }
    }

    /** 让父模型拿到可合并的结果清单；生命周期状态仍由父 run 自己决定。 */
    private static String resultManifest(ArtifactCollector artifacts, RunCompleted completed) {
        List<String> openSteps = completed == null ? List.of() : completed.openSteps();
        return "\n\n<subagent_result>\n"
                + "changed_files: " + artifacts.changedPaths() + "\n"
                + "checks: " + artifacts.checks() + "\n"
                + "open_steps: " + openSteps + "\n"
                + "</subagent_result>";
    }

    /**
     * 直接从子 AgentLoop 的工具 outcome 收集事实；因此即使子运行失败、
     * 没有发出 RunCompleted，已改文件与已跑检查也不会丢失。
     */
    private static final class ArtifactCollector implements ExecutionPolicy {
        private final LinkedHashSet<String> changedPaths = new LinkedHashSet<>();
        private final ArrayList<String> checks = new ArrayList<>();

        @Override public void beforeRequest(List<Message> history) { }

        @Override public void observed(ToolCall call, ToolExecutor.Outcome outcome) {
            if (outcome == null) return;
            changedPaths.addAll(outcome.changedPaths());
            for (String check : outcome.checks()) {
                if (!checks.contains(check)) checks.add(check);
            }
        }

        @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
            return null;
        }

        List<String> changedPaths() {
            return List.copyOf(changedPaths);
        }

        List<String> checks() {
            return List.copyOf(checks);
        }
    }
}
