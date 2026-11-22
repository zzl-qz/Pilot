package com.pilot.server.agent;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.multi.profile.AgentProfileCatalog;
import com.pilot.core.model.ContextComposition;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.model.RequestReport;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PermissionRules;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.project.Project;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.tool.EnableToolsTool;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UseSkillTool;
import com.pilot.core.tool.code.CodeSymbolTool;
import com.pilot.core.tool.command.CommandInputTool;
import com.pilot.core.tool.command.CommandLogsTool;
import com.pilot.core.tool.command.CommandStatusTool;
import com.pilot.core.tool.command.CommandTasksTool;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.core.tool.command.RunCommandTool;
import com.pilot.core.tool.command.StopCommandTool;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.model.ModelSettings;
import com.pilot.server.multi.DispatchAgentsTool;
import com.pilot.server.multi.SpawnAgentTool;
import com.pilot.server.run.ReviewMode;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.skill.InstallSkillTool;
import com.pilot.server.skill.SkillInstallService;
import com.pilot.server.skill.SkillService;
import com.pilot.server.tool.WorkspaceToolFactory;
import com.pilot.server.work.products.ProposeSkillTool;
import com.pilot.server.work.products.PublishArtifactTool;
import com.pilot.server.work.products.SkillDraftService;
import com.pilot.server.work.products.WorkProductService;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/** 为单次 Agent 运行准备工具、技能和上下文；实例不保存运行间状态。 */
@Component
@Slf4j
public final class AgentRunPreparation {
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    private final SessionService sessions;
    private final WorkspaceToolFactory toolFactory;
    private final AgentProperties properties;
    private final MultiAgentProperties multiProperties;
    private final TaskControlService taskControls;
    private final CommandTaskManager commandTasks;
    private final RunSnapshotService snapshots;
    private final PlanRepository plans;
    private final ObjectMapper objectMapper;
    private final SkillService skills;
    private final PreFlightLocator preFlightLocator = new PreFlightLocator();

    private SkillInstallService skillInstaller;
    private WorkProductService workProducts;
    private SkillDraftService skillDrafts;
    private AgentProfileService agentProfiles;
    private ProjectRepository projects;

    public AgentRunPreparation(SessionService sessions, WorkspaceToolFactory toolFactory, AgentProperties properties,
        MultiAgentProperties multiProperties, TaskControlService taskControls,
        CommandTaskManager commandTasks, RunSnapshotService snapshots, PlanRepository plans, ObjectMapper objectMapper,
        SkillService skills) {
        this.sessions = sessions;
        this.toolFactory = toolFactory;
        this.properties = properties;
        this.multiProperties = multiProperties;
        this.taskControls = taskControls;
        this.commandTasks = commandTasks;
        this.snapshots = snapshots;
        this.plans = plans;
        this.objectMapper = objectMapper;
        this.skills = skills;
    }

    @Autowired
    public void setSkillInstaller(SkillInstallService skillInstaller) {
        this.skillInstaller = skillInstaller;
    }

    @Autowired
    public void setWorkProducts(WorkProductService workProducts, SkillDraftService skillDrafts) {
        this.workProducts = workProducts;
        this.skillDrafts = skillDrafts;
    }

    @Autowired(required = false)
    public void setAgentProfiles(AgentProfileService agentProfiles) {
        this.agentProfiles = agentProfiles;
    }

    /** 项目绑定的其他文件夹会作为会话的额外可读写根；没注入时只看默认工作区。 */
    @Autowired(required = false)
    public void setProjects(ProjectRepository projects) {
        this.projects = projects;
    }

    public AgentLoop.Config loopConfig(PreparedRun prepared, String sessionId) {
        return new AgentLoop.Config(properties.maxSteps(), properties.repeatRemindAt(), properties.repeatAbortAt(),
            prepared.systemPrompt(), () -> plans.load(sessionId), properties.maxPlanContinues(),
            properties.contextMaxChars(), properties.completionChecksEnabled());
    }

    public com.pilot.core.model.ModelClient meteredMainClient(PreparedRun prepared,
        com.pilot.core.model.ModelClient delegate, String sessionId, String runId,
        Consumer<RequestReport> reports) {
        return new MeteredModelClient(delegate, sessionId, "main", prepared.promptSources(), reports)
            .withRunContext(sessionId, runId);
    }

    public AgentProfileCatalog profilesFor(String workspace) {
        return agentProfiles == null ? AgentProfileCatalog.EMPTY : agentProfiles.catalogFor(workspace);
    }

    public PreparedRun prepare(PreparationInput input, PlanSink planSink,
        Consumer<ToolRegistry> toolConfigurer) {
        String workspace = sessions.find(input.sessionId()).map(Session::workspace).orElse(null);
        Path resolvedWorkspace = toolFactory.resolve(workspace);
        List<Path> extraRoots = projectFolders(input.sessionId());
        PathValidator workspaceValidator = new PathValidator(resolvedWorkspace, extraRoots);
        ReviewMode review = ReviewMode.parse(input.unattended() ? input.automationGoal() : input.input());
        ToolRegistry tools = review.active() ? reviewTools(workspace, extraRoots)
            : sessionTools(input.sessionId(), input.runId(), workspace, planSink, extraRoots);
        List<String> inheritedDeferred = review.active() ? List.of()
            : inheritedDeferredTools(input.deferredToolSnapshot(), input.history());
        tools.enable(inheritedDeferred);

        SkillCatalog catalog = skills.catalogFor(workspace);
        if (!review.active() && (!catalog.list().isEmpty() || (skills.enabled() && skillInstaller != null))) {
            tools.register(new UseSkillTool(() -> skills.catalogFor(workspace)));
        }
        toolConfigurer.accept(tools);

        Set<String> availableTools = new LinkedHashSet<>(review.active() ? tools.names() : sessionTools(tools));
        addNativeToolNames(availableTools, input.nativeCapabilities());
        List<ContextComposition.Source> promptSources =
            systemPromptParts(input.sessionId(), workspace, availableTools, catalog, review);
        String systemPrompt = promptSources.stream().map(ContextComposition.Source::text)
            .collect(Collectors.joining("\n\n"));
        return new PreparedRun(workspace, resolvedWorkspace, workspaceValidator, review, tools, availableTools,
            promptSources, systemPrompt, inheritedDeferred);
    }

    public void configureExecutionTools(PreparedRun prepared, GoalExecutionPolicy goalPolicy,
        String sessionId, String runId, String workspace, boolean unattended,
        TaskControlService.TaskContract executionTask, com.pilot.core.model.ModelClient measuredChild,
        com.pilot.core.model.ModelClient executionClient, PermissionGate sessionGate, AgentLoop.Config config,
        java.util.function.BooleanSupplier cancelled, SpawnAgentTool.UsageSink childUsage,
        AgentProfileCatalog profiles, com.pilot.core.model.ModelClient configuredModel,
        Consumer<com.pilot.core.event.SessionEvent> sink, com.pilot.core.approval.ApprovalBroker approvals,
        com.pilot.core.session.ConversationRepository conversations, ModelSettings modelSettings,
        com.pilot.server.multi.MultiAgentService multiAgent, com.pilot.server.git.GitWorktrees worktrees) {
        ToolRegistry tools = prepared.tools();
        Set<String> availableTools = prepared.availableTools();
        boolean reviewActive = prepared.review().active();
        List<String> inheritedDeferred = prepared.inheritedDeferredTools();
        if (goalPolicy != null) {
            boolean readOnlySession = reviewActive
                || properties.permissionMode() == com.pilot.core.permission.PermissionMode.READ_ONLY
                || tools.availableTools().stream().noneMatch(t -> t instanceof com.pilot.core.tool.WritesFiles);
            goalPolicy.withCommandLookup(id -> commandTasks.get(sessionId, id)).withReadOnlySession(readOnlySession);
            goalPolicy.withAvailableTools(availableTools)
                .withUpdates(state -> sink.accept(new com.pilot.core.event.ExecutionUpdated(state.mode(), state.phase(),
                    state.requests(), state.reason(), state.gap())));
            tools.register(goalPolicy.promotionTool());
            if (taskControls.load(sessionId).automation() != null) {
                tools.register(goalPolicy.researchTool());
            }
        }
        if (!reviewActive) {
            SpawnAgentTool spawnAgent = new SpawnAgentTool(measuredChild, tools, sessionGate, approvals, config,
                () -> conversations.load(sessionId), sink, cancelled, childUsage, profiles);
            if (configuredModel instanceof com.pilot.server.model.ModelClientProvider provider) {
                spawnAgent.withChildRoute(() -> modelSettings.snapshot().childModel(), provider::clientFor);
            }
            if (worktrees != null) {
                Path base = toolFactory.resolve(workspace);
                spawnAgent.withCheckouts(base, new com.pilot.server.multi.SubagentCheckouts(ignored -> {
                    try {
                        com.pilot.server.git.GitWorktrees.Created created = worktrees.create(base, "");
                        Path checkout = Path.of(created.path());
                        String diff = String.join("\n", worktrees.preview(checkout).files());
                        return new com.pilot.server.multi.SubagentCheckouts.Opened(checkout, diff, "");
                    } catch (RuntimeException failure) {
                        return new com.pilot.server.multi.SubagentCheckouts.Opened(null, "",
                            failure.getMessage() == null ? "无法创建独立检出" : failure.getMessage());
                    }
                }));
                spawnAgent.withWorkspaceTools(path -> toolFactory.registryFor(path.toString()), path -> {
                    com.pilot.server.git.GitWorktrees.Handoff handed = worktrees.handoff(path);
                    return handed.note();
                });
            }
            tools.register(spawnAgent);
            if (multiProperties.enabled()) {
                tools.register(new DispatchAgentsTool(multiAgent, sessionId, runId, workspace,
                    sink, cancelled, executionClient, profiles, childUsage)
                    .withParentPermission(unattended && executionTask.automation().fullAccess()
                        ? com.pilot.core.permission.PermissionMode.AUTO : null));
            }
        }
        tools.enable(inheritedDeferred);
    }

    public String currentTaskContext(String sessionId) {
        return taskControls.stablePrompt(sessionId)
            + "\n历史目标或验收范围不明时，先 read_task_history 恢复用户原文，不能按摘要自行缩小范围。"
            + "\n\n" + PROMPTS.render("system/today", Map.of("today", LocalDate.now().toString())).strip()
            + "\n\n" + planBrief(sessionId);
    }

    public Supplier<String> taskContextSource(String sessionId) {
        return () -> currentTaskContext(sessionId);
    }

    public List<Message> activateSkill(List<Message> history, String workspace, String requestedSkill) {
        if (requestedSkill == null) {
            return history;
        }
        String activate = skills.activateText(skills.catalogFor(workspace), requestedSkill);
        if (activate == null) {
            activate = "（用户指定了技能 " + requestedSkill + "，但该技能当前不可用：未找到或校验未通过。）";
        }
        List<Message> seeded = new ArrayList<>(history);
        seeded.add(Message.user(activate));
        return List.copyOf(seeded);
    }

    public PermissionGate withWorkspaceRules(PermissionGate base, Path workspace) {
        PermissionRules rules = PermissionRules.load(workspace);
        if (rules.isEmpty()) {
            return base;
        }
        return (call, tool) -> {
            var baseline = base.check(call, tool);
            if (baseline.decision() == PermissionDecision.DENY) {
                return baseline;
            }
            var ruled = rules.evaluate(call);
            return ruled != null ? ruled : baseline;
        };
    }

    public com.pilot.core.tool.ReadGuard readGuardOf(ToolRegistry tools) {
        for (com.pilot.core.tool.Tool tool : tools.tools()) {
            if (tool instanceof ReadFileTool read) {
                return read.guard();
            }
        }
        return null;
    }

    public PreFlightLocator.Result configurePreflight(AgentLoop loop, PreparedRun prepared, String input,
        boolean continuation) {
        if (continuation || !properties.preflightLocalizationEnabledValue()) {
            return null;
        }
        PreFlightLocator.Result preflight = preFlightLocator.locate(prepared.resolvedWorkspace(),
            prepared.workspaceValidator(), input, Set.of());
        if (preflight.hasCandidates()) {
            loop.withInitialPhaseHint(preflight.renderBlock(), preflight.candidatePaths());
        }
        loop.withSteeringHintProvider(text -> preFlightLocator.locate(prepared.resolvedWorkspace(),
            prepared.workspaceValidator(), text, loop.observedPathsSnapshot()).renderBlock());
        return preflight;
    }

    public void refreshPlanPhaseHint(AgentLoop activeLoop, Path workspaceRoot, PathValidator validator,
        List<PlanItem> before, List<PlanItem> after) {
        if (activeLoop == null || !properties.preflightLocalizationEnabledValue() || after == null || after.isEmpty()) {
            return;
        }
        long beforeDone = before == null ? 0 : before.stream().filter(PlanItem::done).count();
        long afterDone = after.stream().filter(PlanItem::done).count();
        if (afterDone <= beforeDone) {
            return;
        }
        String beforeActive = activeStepOf(before);
        String afterActive = activeStepOf(after);
        if (afterActive.isBlank() || afterActive.equals(beforeActive)) {
            return;
        }
        PreFlightLocator.Result next =
            preFlightLocator.locate(workspaceRoot, validator, afterActive, activeLoop.observedPathsSnapshot());
        if (next.hasCandidates()) {
            activeLoop.refreshPhaseHint(next.renderBlock(), next.candidatePaths());
        }
    }

    public void logPreflightTelemetry(String runId, PreFlightLocator.Result preflight, Set<String> observedPaths,
        List<String> changedFiles) {
        if (preflight == null) {
            return;
        }
        if (!preflight.hasCandidates()) {
            log.debug("Pre-flight localization skipped run={}: elapsedMs={}, anchors={}, timedOut={}, reason={}", runId,
                preflight.elapsedMs(), preflight.anchorCount(), preflight.timedOut(), preflight.skipReason());
            return;
        }
        Set<String> normalizedObserved = observedPaths.stream().filter(Objects::nonNull)
            .map(path -> path.strip().replace('\\', '/')).collect(Collectors.toSet());
        Set<String> normalizedChanged = changedFiles.stream().filter(Objects::nonNull)
            .map(path -> path.strip().replace('\\', '/')).collect(Collectors.toSet());
        Set<String> candidates = preflight.candidatePaths();
        long hitRead = candidates.stream().filter(normalizedObserved::contains).count();
        long hitEdit = candidates.stream().filter(normalizedChanged::contains).count();
        log.info("Pre-flight localization run={}: elapsedMs={}, anchors={}, candidates={}, hitRead={}, hitEdit={}, timedOut={}",
            runId, preflight.elapsedMs(), preflight.anchorCount(), candidates.size(), hitRead, hitEdit,
            preflight.timedOut());
    }

    private static String activeStepOf(List<PlanItem> items) {
        if (items == null) {
            return "";
        }
        return items.stream().filter(item -> PlanItem.IN_PROGRESS.equals(item.status())).map(PlanItem::step).findFirst()
            .orElse("");
    }

    private List<ContextComposition.Source> systemPromptParts(String sessionId, String workspace,
        Set<String> availableTools, SkillCatalog catalog, ReviewMode review) {
        Map<String, String> variables = new LinkedHashMap<>(PromptLibrary.environment(workspace));
        variables.put("skills", skills.catalogText(catalog));
        List<ContextComposition.Source> parts = new ArrayList<>(PROMPTS.systemParts(variables, availableTools));
        String rules = projectRules(workspace);
        if (!rules.isEmpty()) {
            parts.add(new ContextComposition.Source("rules", rules));
        }
        String memory = ProjectRuleLoader.loadMemory(toolFactory.resolve(workspace), PROMPTS);
        if (!memory.isEmpty()) {
            parts.add(new ContextComposition.Source("rules", memory));
        }
        if (review.active()) {
            parts.add(new ContextComposition.Source("system",
                PROMPTS.render("review/protocol", Map.of("scope", review.scopeText())).strip()));
        }
        return List.copyOf(parts);
    }

    private static void addNativeToolNames(Set<String> names, Set<NativeCapability> capabilities) {
        if (capabilities.contains(NativeCapability.WEB_SEARCH)) {
            names.add(ToolNames.WEB_SEARCH);
        }
        if (capabilities.contains(NativeCapability.WEB_FETCH)) {
            names.add(ToolNames.WEB_FETCH);
        }
    }

    /** 会话所属项目绑定的其他文件夹；会话只存一个默认工作目录，其余目录按项目取。 */
    private List<Path> projectFolders(String sessionId) {
        if (projects == null) {
            return List.of();
        }
        return sessions.find(sessionId).map(Session::projectId).flatMap(projects::find).map(Project::folders)
            .orElse(List.of()).stream().map(Path::of).toList();
    }

    private ToolRegistry reviewTools(String workspace, List<Path> extraRoots) {
        AgentScope scope = AgentScope.readOnly(List.of(), List.of())
            .withTools(List.of("read_*", "list_*", "glob_*", "search_*", CodeSymbolTool.NAME, ToolNames.RUN_COMMAND,
                ToolNames.WEB_SEARCH, ToolNames.WEB_FETCH), List.of());
        return toolFactory.registryForScope(workspace, scope, SandboxSink.rejectAll(), extraRoots);
    }

    private Set<String> sessionTools(ToolRegistry base) {
        Set<String> names = new LinkedHashSet<>(base.names());
        names.add(ToolNames.SPAWN_AGENT);
        if (multiProperties.enabled()) {
            names.add(ToolNames.DISPATCH_AGENTS);
        }
        return Set.copyOf(names);
    }

    private String projectRules(String workspace) {
        Path root;
        try {
            root = toolFactory.resolve(workspace);
        } catch (RuntimeException failure) {
            return "";
        }
        return ProjectRuleLoader.load(root, PROMPTS);
    }

    private String planBrief(String sessionId) {
        List<PlanItem> items = plans.load(sessionId);
        if (items.isEmpty()) {
            return "";
        }
        List<PlanItem> open = items.stream().filter(PlanItem::open).toList();
        if (open.isEmpty()) {
            return PROMPTS.render("system/plan-done", Map.of()).strip();
        }
        String list = open.stream().map(item -> "- [" + item.status() + "] " + item.step())
            .collect(Collectors.joining("\n"));
        return PROMPTS.render("system/plan-context", Map.of("planItems", list)).strip();
    }

    private ToolRegistry sessionTools(String sessionId, String runId, String workspace, PlanSink planSink,
        List<Path> extraRoots) {
        ToolRegistry tools = new ToolRegistry();
        toolFactory.usePlan(() -> plans.load(sessionId));
        try {
            toolFactory.registryFor(workspace, extraRoots, planSink).tools().stream()
                .filter(tool -> !ToolNames.RUN_COMMAND.equals(tool.name())
                    && !EnableToolsTool.NAME.equals(tool.name()))
                .forEach(tools::register);
        } finally {
            toolFactory.usePlan(null);
        }
        tools.register(new EnableToolsTool(tools));
        RunCommandTool commands = new RunCommandTool(new PathValidator(toolFactory.resolve(workspace), extraRoots),
            commandTasks, sessionId, snapshots);
        for (com.pilot.core.tool.Tool tool : tools.tools()) {
            if (tool instanceof ReadFileTool read) {
                commands.withReads(read.guard());
            }
        }
        tools.register(commands);
        tools.register(new CommandStatusTool(commandTasks, sessionId, snapshots));
        tools.register(new CommandTasksTool(commandTasks));
        tools.register(new CommandLogsTool(commandTasks, sessionId));
        tools.register(new CommandInputTool(commandTasks, sessionId));
        tools.register(new StopCommandTool(commandTasks, sessionId));
        tools.register(new ReadTaskHistoryTool(sessions, sessionId));
        if (workProducts != null) {
            tools.register(new PublishArtifactTool(workProducts, objectMapper, sessionId, runId));
        }
        if (skillDrafts != null && skills != null && skills.enabled()) {
            tools.register(new ProposeSkillTool(skillDrafts, objectMapper, sessionId, runId));
        }
        if (skillInstaller != null && skills != null && skills.enabled()) {
            tools.register(new InstallSkillTool(skillInstaller, skills, objectMapper, workspace));
        }
        toolFactory.sessionExtras(sessionId, workspace).forEach(tools::register);
        return tools;
    }

    private List<String> inheritedDeferredTools(List<String> remembered, List<Message> history) {
        if (remembered != null) {
            return remembered;
        }
        List<String> called = new ArrayList<>();
        for (Message message : history) {
            for (ToolCall call : message.toolCalls()) {
                if (EnableToolsTool.NAME.equals(call.name())) {
                    openedNames(call).stream().filter(name -> !called.contains(name)).forEach(called::add);
                } else if (!called.contains(call.name())) {
                    called.add(call.name());
                }
            }
        }
        return List.copyOf(called);
    }

    private static List<String> openedNames(ToolCall call) {
        Object raw = call.args() == null ? null : call.args().get("names");
        if (raw instanceof List<?> values) {
            return values.stream().filter(Objects::nonNull).map(Object::toString).map(String::strip)
                .filter(name -> !name.isEmpty()).toList();
        }
        return raw == null || raw.toString().isBlank() ? List.of() : List.of(raw.toString().strip());
    }

    public record PreparationInput(String sessionId, String runId, String input, String requestedSkill,
        boolean continuation, List<Message> history, boolean unattended, String automationGoal, boolean fullAccess,
        Set<NativeCapability> nativeCapabilities, List<String> deferredToolSnapshot) {
        public PreparationInput {
            history = List.copyOf(history);
            nativeCapabilities = Set.copyOf(nativeCapabilities);
            deferredToolSnapshot = deferredToolSnapshot == null ? null : List.copyOf(deferredToolSnapshot);
        }
    }

    public record PreparedRun(String workspace, Path resolvedWorkspace, PathValidator workspaceValidator,
        ReviewMode review, ToolRegistry tools, Set<String> availableTools,
        List<ContextComposition.Source> promptSources, String systemPrompt, List<String> inheritedDeferredTools) {
        public PreparedRun {
            availableTools = Set.copyOf(availableTools);
            promptSources = List.copyOf(promptSources);
            inheritedDeferredTools = List.copyOf(inheritedDeferredTools);
        }
    }
}
