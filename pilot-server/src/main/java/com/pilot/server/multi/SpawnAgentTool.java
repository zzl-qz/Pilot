package com.pilot.server.multi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.ExecutionPolicy;
import com.pilot.core.loop.ToolExecutor;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.multi.profile.AgentProfile;
import com.pilot.core.multi.profile.AgentProfileCatalog;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PermissionResult;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.workspace.AgentScope;

/**
 * {@code spawn_agent}：把一段「读得多、结论短」的探索派给子代理。
 *
 * <p>子代理拥有独立的上下文，只把最终结论回传给父代理，主上下文不会被子代理读过的文件撑爆。
 * 第一版是同步执行（父代理等待），因此不存在并发写同一个工作区的问题；
 * 子代理的工具集不包含 spawn_agent，避免递归派生。
 */
public final class SpawnAgentTool implements Tool.ParallelReadOnly {
    private static final String ISOLATION_PROMPT = "[子代理隔离] 你是只读探索。只完成派发的子任务，把结论交回父代理。不能改文件，不能跑命令，不能创建 Goal，不能再派生子代理。";
    /** 大项目跨包目录定位 + 读取多个源文件通常需要 12~16 步；默认给 18 步工作请求 + 1 次阶段收尾。 */
    static final int DEFAULT_MAX_SUBAGENT_STEPS = 18;
    /** 一个父 run 最多派生的子任务数；续跑会创建新 run，但累计 Goal 预算不会清零。 */
    static final int DEFAULT_MAX_SPAWNS_PER_RUN = 6;
    /** 所有子任务共享的模型请求池，不能靠不断更换 task 文案绕过单子任务上限。 */
    static final int DEFAULT_MAX_SUBAGENT_REQUESTS_PER_RUN = 54;
    /** 同一轮只读子代理同时运行的上限。第 5 个等待空位，而不是失败。 */
    static final int MAX_PARALLEL_SUBAGENTS = 4;
    private static final Set<String> FORBIDDEN_TOOLS =
        Set.of(ToolNames.SPAWN_AGENT, ToolNames.DISPATCH_AGENTS, ToolNames.PROMOTE_GOAL, "create_goal", "update_goal",
            ToolNames.UPDATE_PLAN, ToolNames.STOP_COMMAND, ToolNames.WAIT_UNTIL, ToolNames.RECORD_RESEARCH, "ask_user");
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    /** 子代理单轮预算：支持大型多模块仓库按需调优，同时测试可压小步数快速验证边界。 */
    public record Budget(int maxSteps, int maxSpawnsPerRun, int maxRequestsPerRun) {
        public static final Budget DEFAULT =
            new Budget(DEFAULT_MAX_SUBAGENT_STEPS, DEFAULT_MAX_SPAWNS_PER_RUN, DEFAULT_MAX_SUBAGENT_REQUESTS_PER_RUN);

        public Budget {
            maxSteps = maxSteps < 2 ? DEFAULT_MAX_SUBAGENT_STEPS : Math.min(maxSteps, 60);
            maxSpawnsPerRun = maxSpawnsPerRun < 1 ? DEFAULT_MAX_SPAWNS_PER_RUN : Math.min(maxSpawnsPerRun, 16);
            maxRequestsPerRun = maxRequestsPerRun < maxSteps ? maxSteps * 2 : Math.min(maxRequestsPerRun, 240);
        }
    }

    private final ModelClient client;
    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentLoop.Config config;
    private final Supplier<List<Message>> history;
    private final Consumer<SessionEvent> events;
    private final BooleanSupplier cancelled;
    private final UsageSink usageSink;
    /** 本 run 可用的自定义代理目录；空目录时 agent 参数只能引用失败。 */
    private final AgentProfileCatalog profiles;
    private final Budget budget;
    private final AtomicInteger runSpawns = new AtomicInteger();
    private final Semaphore parallelSlots = new Semaphore(MAX_PARALLEL_SUBAGENTS);
    private final AtomicInteger runRequests = new AtomicInteger();
    private Path checkoutWorkspace;
    private SubagentCheckouts checkouts;
    private Function<AgentScope, ToolRegistry> scopedTools;
    private Function<Path, ToolRegistry> rootedTools = path -> null;
    private Function<Path, String> handoff = path -> "";
    /** 已配置的替代模型。空表示只能用父模型或子路由。 */
    private Map<String, ModelClient> alternateModels = Map.of();
    private Supplier<String> childRoute = () -> "";
    private Function<String, ModelClient> routedModels = name -> null;
    /** 只读子代理的对话，供 resumeAgentId 接着问。进程内有效，不写入会话库。 */
    private final Map<String, List<Message>> transcripts = new ConcurrentHashMap<>();

    public SpawnAgentTool withModels(Map<String, ModelClient> models) {
        this.alternateModels = models == null || models.isEmpty() ? Map.of() : Map.copyOf(models);
        return this;
    }

    /** 未在本次调用里写模型名时使用子路由。路由和单次名字都用同一个端点构造客户端。 */
    public SpawnAgentTool withChildRoute(Supplier<String> route, Function<String, ModelClient> models) {
        this.childRoute = route == null ? () -> "" : route;
        this.routedModels = models == null ? name -> null : models;
        return this;
    }

    /** 为每次只读子代理按 AgentScope 现场构造独立工具表（含独立 ReadTracker 与路径/命令作用域硬隔离）。 */
    public SpawnAgentTool withScopedTools(Function<AgentScope, ToolRegistry> factory) {
        this.scopedTools = factory;
        return this;
    }

    /** 写模式的独立检出使用这份按目录重建的工具，结束后可以把差异交回父目录。 */
    public SpawnAgentTool withWorkspaceTools(Function<Path, ToolRegistry> rooted, Function<Path, String> handoff) {
        this.rootedTools = rooted == null ? path -> null : rooted;
        this.handoff = handoff == null ? path -> "" : handoff;
        return this;
    }

    public SpawnAgentTool withCheckouts(Path workspace, SubagentCheckouts checkouts) {
        this.checkoutWorkspace = workspace;
        this.checkouts = checkouts;
        return this;
    }

    /** 把子请求计入父任务累计事故熔断；Goal 关闭时使用 no-op，run 级池仍然生效。 */
    public interface UsageSink {
        void beforeRequest();

        void afterRequest(long activeMillis, ModelUsage usage);

        static UsageSink none() {
            return new UsageSink() {
                @Override
                public void beforeRequest() {
                }

                @Override
                public void afterRequest(long activeMillis, ModelUsage usage) {
                }
            };
        }
    }

    public SpawnAgentTool(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
        ApprovalBroker approvals, AgentLoop.Config config, Supplier<List<Message>> history,
        Consumer<SessionEvent> events, BooleanSupplier cancelled) {
        this(client, tools, permissionGate, approvals, config, history, events, cancelled, UsageSink.none());
    }

    SpawnAgentTool(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, ApprovalBroker approvals,
        AgentLoop.Config config, Supplier<List<Message>> history, Consumer<SessionEvent> events,
        BooleanSupplier cancelled, UsageSink usageSink) {
        this(client, tools, permissionGate, approvals, config, history, events, cancelled, usageSink,
            AgentProfileCatalog.EMPTY);
    }

    public SpawnAgentTool(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
        ApprovalBroker approvals, AgentLoop.Config config, Supplier<List<Message>> history,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, UsageSink usageSink, AgentProfileCatalog profiles) {
        this(client, tools, permissionGate, approvals, config, history, events, cancelled, usageSink, profiles,
            Budget.DEFAULT);
    }

    public SpawnAgentTool(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
        ApprovalBroker approvals, AgentLoop.Config config, Supplier<List<Message>> history,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, UsageSink usageSink, AgentProfileCatalog profiles,
        Budget budget) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tools = isolatedTools(Objects.requireNonNull(tools, "tools must not be null"));
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.budget = budget == null ? Budget.DEFAULT : budget;
        this.config = SubagentSupport.subagentConfig(Objects.requireNonNull(config, "config must not be null"),
            this.budget.maxSteps(), ISOLATION_PROMPT);
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
        this.usageSink = Objects.requireNonNull(usageSink, "usageSink must not be null");
        this.profiles = profiles == null ? AgentProfileCatalog.EMPTY : profiles;
    }

    @Override
    public String name() {
        return ToolNames.SPAWN_AGENT;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("task", ToolSchemas.string("子任务的完整描述，包含目标、范围与期望产出"), "agent",
                ToolSchemas.string(agentFieldDescription()), "name",
                ToolSchemas.string("子任务的简短名字（10 字以内，如「统计 Java 文件」），用于界面展示，可省略"), "fork_turns",
                Map.of("type", "string", "enum", List.of("none", "all"), "description",
                    "none = 不带父对话（默认，推荐）；all = 带上当前对话记忆"),
                "isolation",
                Map.of("type", "string", "enum", List.of("none", "worktree"), "description",
                    "write 模式可使用 worktree。只读子代理即使传入 worktree 也不会创建检出"),
                "mode", Map.of("type", "string", "enum", List.of("read", "write"), "description", "默认 read"),
                "resumeAgentId", ToolSchemas.string("继续同一个只读子代理。不填则新开"), "model",
                ToolSchemas.string("只读子代理使用的已配置模型。不填则用父模型")),
            List.of("task"));
    }

    /** agent 字段的动态说明：有可用代理时点名角色，模型不必猜名字。 */
    private String agentFieldDescription() {
        StringBuilder text = new StringBuilder(
            "引用自定义子代理（.pilot/agents/<name>.md）：工具名单按定义裁剪，角色提示词注入子代理。" + "子代理仍保持只读探索语义，可写代理请用 dispatch_agents");
        String names = profiles.nameListText();
        if (!names.isEmpty()) {
            text.append("。可用代理：").append(names);
        }
        return text.toString();
    }

    /**
     * 按只读对待：子代理的工具表在构造时就被裁剪成父级只读工具，写文件、命令、派生和 Goal 控制
     * 都不在表里，因此它在 READ_ONLY 会话里也是安全的，不需要每次额外审批。
     */
    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        return !"write".equals(SubagentSupport.text(args == null ? null : args.get("mode")));
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String task = args == null ? "" : SubagentSupport.text(args.get("task"));
        if (task.isBlank()) {
            return ToolResult.text("ERROR: task 不能为空");
        }
        String fork = args == null ? "" : SubagentSupport.text(args.get("fork_turns"));
        boolean inherit = "all".equals(fork);
        String resumeId = args == null ? "" : SubagentSupport.text(args.get("resumeAgentId"));
        if (!resumeId.isEmpty() && "write".equals(SubagentSupport.text(args.get("mode")))) {
            return ToolResult.text("ERROR: 续跑只支持只读子代理");
        }
        List<Message> prior = List.of();
        if (!resumeId.isEmpty()) {
            prior = transcripts.get(resumeId);
            if (prior == null) {
                return ToolResult.text("ERROR: 没有找到子代理 " + resumeId + " 的已有结论，无法继续");
            }
        }
        String requestedModel = args == null ? "" : SubagentSupport.text(args.get("model"));
        ModelClient chosen = client;
        if (!requestedModel.isEmpty()) {
            ModelClient found = alternateModels.get(requestedModel);
            if (found == null) {
                found = routedModels.apply(requestedModel);
            }
            if (found == null) {
                String names = String.join("、", alternateModels.keySet());
                return ToolResult
                    .text("ERROR: 模型 " + requestedModel + " 未配置（可用：" + (names.isEmpty() ? "仅父模型" : names) + "）");
            }
            chosen = found;
        } else {
            try {
                chosen = SubagentSupport.resolveRoutedClient(client, childRoute.get(), routedModels);
            } catch (IllegalStateException e) {
                return ToolResult.text("ERROR: " + e.getMessage());
            }
        }

        // 自定义代理：名字必须在目录里存在，拿不到明确报错而不是静默退回普通子代理
        String agentName = args == null ? "" : SubagentSupport.text(args.get("agent"));
        AgentProfile profile = null;
        if (!agentName.isEmpty()) {
            profile = profiles.find(agentName).orElse(null);
            if (profile == null) {
                String available = profiles.nameListText();
                return ToolResult
                    .text("ERROR: 引用的代理 " + agentName + " 不存在（可用：" + (available.isEmpty() ? "无" : available) + "）");
            }
            if (!profile.writable() && "write".equals(SubagentSupport.text(args.get("mode")))) {
                return ToolResult.text("ERROR: 代理 " + agentName + " 配置为只读（writable=false），禁止以 write 模式运行");
            }
        }
        String displayName = args == null || SubagentSupport.text(args.get("name")).isEmpty() ? agentName
            : SubagentSupport.text(args.get("name"));
        String checkoutNote = "";
        Path checkoutPath = null;
        if ("worktree".equals(SubagentSupport.text(args == null ? null : args.get("isolation")))
            && "write".equals(SubagentSupport.text(args.get("mode")))) {
            if (checkouts == null || checkoutWorkspace == null) {
                return ToolResult.text("ERROR: 无法创建独立检出。未修改父工作区。");
            }
            SubagentCheckouts.Opened opened = checkouts.acquire(checkoutWorkspace);
            if (!opened.ok()) {
                return ToolResult.text("ERROR: " + opened.error());
            }
            checkoutPath = opened.path();
            checkoutNote = "\n\n独立检出：" + opened.path()
                + (opened.diff() == null || opened.diff().isBlank() ? "" : "\n" + opened.diff());
        }

        try {
            if (resumeId.isEmpty()) {
                int spawn = runSpawns.incrementAndGet();
                if (spawn > budget.maxSpawnsPerRun()) {
                    return ToolResult
                        .text("ERROR: 本轮子代理派生次数已达到 " + budget.maxSpawnsPerRun() + " 次；请先合并已有结果，由父代理继续执行或阶段交付。");
                }
            }

            String agentId = resumeId.isEmpty() ? "agent_" + UUID.randomUUID().toString().substring(0, 8) : resumeId;
            events.accept(new SubagentStarted(agentId, profile == null ? task : profile.prefixGoal(task), displayName));
            ArtifactCollector artifacts = new ArtifactCollector();
            AtomicReference<RunCompleted> completion = new AtomicReference<>();
            boolean slotHeld = false;
            boolean finishedSent = false;
            try {
                parallelSlots.acquire();
                slotHeld = true;
                // 只读子代理优先按 AgentScope 现场构造独立工具表（独立 ReadTracker + 路径/命令作用域硬隔离）；
                // 写隔离 worktree 使用独立非缓存工具表，并叠加 profile 作用域门禁。
                ToolRegistry effectiveTools = buildChildTools(checkoutPath, profile);
                PermissionGate effectiveGate =
                    profile == null ? permissionGate : scopedPermissionGate(permissionGate, profile);
                AgentLoop.Config effectiveConfig = profile == null ? this.config : roleConfig(this.config, profile);
                AgentLoop loop = new AgentLoop(boundedClient(chosen), effectiveTools, effectiveGate, approvals,
                    effectiveConfig, cancelled::getAsBoolean).withExecutionPolicy(artifacts);
                List<Message> context = resumeId.isEmpty() ? (inherit ? history.get() : List.of()) : prior;
                String goal = profile == null ? task : profile.prefixGoal(task);
                String prompt = resumeId.isEmpty() ? PROMPTS.render("subagent/task", Map.of("task", goal)).strip()
                    : "继续同一个只读子任务。历史里已有结论，不要从头重读。新问题：" + goal;
                if (checkoutPath != null) {
                    prompt = prompt + "\n工作目录是独立检出 " + checkoutPath + "，不要改父目录。";
                }
                // 子代理有独立累计器；子 run 的终止事件绝不能流入父 sink，否则会提前完成/中断父任务。
                AgentLoop.Result result = loop.run(prompt, context,
                    event -> SubagentSupport.forwardChildEvent(events, event, agentId, completion, true));
                remember(agentId, result);
                String manifest = resultManifest(artifacts, completion.get());
                String closing = SubagentSupport.truncateSummary(result.finalText());
                if (result.failed()) {
                    String code = result.failureCode() == null ? "UNKNOWN" : result.failureCode();
                    if (SubagentSupport.hasAuthoredConclusion(closing, code)) {
                        // 对齐 DSH 结算通知：输出提取与停止原因解耦，触顶阶段总结必须完整交回父代理，避免父代理重抓一遍。
                        String header = "[子代理阶段结算：" + settlementNote(code) + "]";
                        String report = header + "\n\n" + closing + manifest + agentLine(agentId);
                        events.accept(new SubagentFinished(agentId, report, false, code, true));
                        finishedSent = true;
                        return new ToolResult(report, artifacts.changedPaths(), artifacts.checks());
                    }
                    String reason = "子代理未完成：" + code;
                    String report = "ERROR: " + reason + manifest;
                    events.accept(new SubagentFinished(agentId, report, false, code, false));
                    finishedSent = true;
                    return new ToolResult(report, artifacts.changedPaths(), artifacts.checks());
                }
                String summary = closing + manifest;
                String report = closing.isBlank() ? "子代理完成，但没有返回文本结论。" + manifest : summary;
                if (checkoutPath != null) {
                    String handed = handoff.apply(checkoutPath);
                    if (handed != null && !handed.isBlank()) {
                        checkoutNote = checkoutNote + "\n" + handed;
                    }
                }
                events.accept(new SubagentFinished(agentId, summary, true, "COMPLETED", false));
                finishedSent = true;
                return new ToolResult(report + agentLine(agentId) + checkoutNote, artifacts.changedPaths(),
                    artifacts.checks());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String report = "ERROR: 等待子代理空位时被中断";
                if (!finishedSent) {
                    events.accept(new SubagentFinished(agentId, report, false, "INTERRUPTED", false));
                    finishedSent = true;
                }
                return ToolResult.text(report);
            } catch (RuntimeException e) {
                String reason = "子代理异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                String report = "ERROR: " + reason + resultManifest(artifacts, completion.get());
                if (!finishedSent) {
                    events.accept(new SubagentFinished(agentId, report, false, "RUNTIME_ERROR", false));
                }
                return new ToolResult(report, artifacts.changedPaths(), artifacts.checks());
            } finally {
                if (slotHeld) {
                    parallelSlots.release();
                }
            }
        } finally {
            if (checkoutPath != null) {
                checkouts.release(checkoutPath);
            }
        }
    }

    private void remember(String agentId, AgentLoop.Result result) {
        if (result == null || result.conversation() == null || result.conversation().isEmpty()) {
            return;
        }
        if (result.finalText() == null || result.finalText().isBlank()) {
            return;
        }
        transcripts.put(agentId, List.copyOf(result.conversation()));
    }

    private static String agentLine(String agentId) {
        return "\nagent_id: " + agentId;
    }

    private static String settlementNote(String code) {
        if (RunFailureCode.MAX_STEPS.is(code)) {
            return "达到探索步数上限（MAX_STEPS），以下为已收集证据的阶段结论；未核实部分由父代理按需补查";
        }
        return "提前结束（" + code + "），以下为已收集证据的阶段结论";
    }

    /** 将重试与收尾也计入独立请求保险丝；正常路径是 maxSteps 个工作请求 + 1 个阶段收尾。 */
    private ModelClient boundedClient(ModelClient delegate) {
        int maxRequests = budget.maxSteps() + 1;
        return SubagentSupport.boundedClient(delegate, maxRequests, "子代理已达到独立模型请求上限 " + maxRequests + "，返回已有证据给父代理收尾。",
            runRequests, budget.maxRequestsPerRun(),
            "本轮所有子代理累计模型请求已达到 " + budget.maxRequestsPerRun() + "，请由父代理合并已有证据并收尾。", usageSink);
    }

    private ToolRegistry buildChildTools(Path checkoutPath, AgentProfile profile) {
        if (checkoutPath != null) {
            ToolRegistry rooted = rootedTools.apply(checkoutPath);
            ToolRegistry base = rooted == null ? this.tools : withoutRecursion(rooted);
            return profile == null ? base : filterTools(base, profile);
        }
        if (scopedTools != null) {
            AgentScope scope =
                profile == null ? AgentScope.readOnly(List.of(), List.of()) : profile.scope().withWritable(false);
            ToolRegistry built = scopedTools.apply(scope);
            if (built != null) {
                return withoutRecursion(built);
            }
        }
        return profile == null ? this.tools : filterTools(this.tools, profile);
    }

    /** 在权限门层强制校验 AgentProfile 的 files/denyFiles/commands/denyCommands/writable，兜底保护所有工具路径。 */
    private static PermissionGate scopedPermissionGate(PermissionGate base, AgentProfile profile) {
        AgentScope scope = profile.scope();
        return (call, tool) -> {
            if (!scope.allowsTool(call.name())) {
                return PermissionResult.deny("代理 " + profile.name() + " 不允许使用工具 " + call.name());
            }
            Map<String, Object> callArgs = call.args();
            if (!scope.writable() && !tool.readOnly(callArgs)) {
                return PermissionResult.deny("代理 " + profile.name() + " 为只读角色，禁止写操作 " + call.name());
            }
            if (ToolNames.RUN_COMMAND.equals(call.name()) && callArgs != null) {
                String cmd = SubagentSupport.text(callArgs.get("command"));
                if (!cmd.isEmpty() && !scope.allowsCommand(cmd)) {
                    return PermissionResult.deny(scope.commandHint(cmd));
                }
            }
            if (callArgs != null && (!scope.allowFiles().isEmpty() || !scope.denyFiles().isEmpty())) {
                for (String key : List.of("path", "from", "to", "source", "destination")) {
                    String raw = SubagentSupport.text(callArgs.get(key));
                    if (!raw.isEmpty() && !scope.allowsPath(raw) && !scope.allowsDirectory(raw)) {
                        return PermissionResult.deny("路径 " + raw + " 不在代理 " + profile.name() + " 允许范围内");
                    }
                }
            }
            return base.check(call, tool);
        };
    }

    private static ToolRegistry withoutRecursion(ToolRegistry source) {
        ToolRegistry copy = new ToolRegistry();
        source.tools().stream().filter(tool -> !FORBIDDEN_TOOLS.contains(tool.name())).forEach(copy::register);
        return copy;
    }

    /** 在父工具注册表继续变化前就冻结一份能力快照，并移除所有派生/目标控制工具。 */
    private static ToolRegistry isolatedTools(ToolRegistry source) {
        ToolRegistry isolated = new ToolRegistry();
        source.tools().stream().filter(tool -> tool.readOnly() && !FORBIDDEN_TOOLS.contains(tool.name()))
            .forEach(isolated::register);
        return isolated;
    }

    /** 自定义代理的工具裁剪：在已隔离的只读表上按 profile 名单再过滤，名单外的工具不存在。 */
    static ToolRegistry filterTools(ToolRegistry source, AgentProfile profile) {
        ToolRegistry filtered = new ToolRegistry();
        source.tools().stream().filter(tool -> profile.scope().allowsTool(tool.name())).forEach(filtered::register);
        return filtered;
    }

    /** 角色提示词注入子代理系统提示词（隔离段保留，只加身份设定）。 */
    static AgentLoop.Config roleConfig(AgentLoop.Config config, AgentProfile profile) {
        return new AgentLoop.Config(config.maxSteps(), config.repeatRemindAt(), config.repeatAbortAt(),
            config.systemPrompt() + "\n\n[角色设定]\n" + profile.prompt(), config.plan(), config.maxPlanContinues(),
            config.contextLimit(), config.completionChecks());
    }

    /** 让父模型拿到可合并的结果清单；生命周期状态仍由父 run 自己决定。 */
    private static String resultManifest(ArtifactCollector artifacts, RunCompleted completed) {
        List<String> openSteps = completed == null ? List.of() : completed.openSteps();
        return "\n\n<subagent_result>\n" + "changed_files: " + artifacts.changedPaths() + "\n" + "checks: "
            + artifacts.checks() + "\n" + "open_steps: " + openSteps + "\n" + "</subagent_result>";
    }

    /**
     * 直接从子 AgentLoop 的工具 outcome 收集事实；因此即使子运行失败、
     * 没有发出 RunCompleted，已改文件与已跑检查也不会丢失。
     */
    private static final class ArtifactCollector implements ExecutionPolicy {
        private final LinkedHashSet<String> changedPaths = new LinkedHashSet<>();
        private final ArrayList<String> checks = new ArrayList<>();

        @Override
        public void beforeRequest(List<Message> history) {
        }

        @Override
        public void observed(ToolCall call, ToolExecutor.Outcome outcome) {
            if (outcome == null) {
                return;
            }
            changedPaths.addAll(outcome.changedPaths());
            for (String check : outcome.checks()) {
                if (!checks.contains(check)) {
                    checks.add(check);
                }
            }
        }

        @Override
        public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
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
