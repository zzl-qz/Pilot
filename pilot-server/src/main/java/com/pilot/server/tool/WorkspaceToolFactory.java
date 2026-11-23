package com.pilot.server.tool;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserTool;
import com.pilot.core.exec.ExecutionBackends.Selection;
import com.pilot.core.mcp.McpCallTool;
import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpToolsTool;
import com.pilot.core.message.ToolNames;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.tool.AskUserTool;
import com.pilot.core.tool.EnableToolsTool;
import com.pilot.core.tool.QuestionBroker;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.RememberTool;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UpdatePlanTool;
import com.pilot.core.tool.WebFetchTool;
import com.pilot.core.tool.WebSearchTool;
import com.pilot.core.tool.code.CodeDiagnosticsTool;
import com.pilot.core.tool.code.CodeSymbolTool;
import com.pilot.core.tool.code.LocalCodeChecker;
import com.pilot.core.tool.command.CommandChangeTracker;
import com.pilot.core.tool.command.PrepareRuntimeTool;
import com.pilot.core.tool.command.RunCommandTool;
import com.pilot.core.tool.command.SandboxCommandTool;
import com.pilot.core.tool.file.ApplyPatchTool;
import com.pilot.core.tool.file.CreateFileTool;
import com.pilot.core.tool.file.EditFileTool;
import com.pilot.core.tool.file.GlobFilesTool;
import com.pilot.core.tool.file.ListDirTool;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.tool.file.SandboxCreateFileTool;
import com.pilot.core.tool.file.SandboxEditFileTool;
import com.pilot.core.tool.file.SearchTool;
import com.pilot.core.tool.office.OfficeTool;
import com.pilot.core.tool.process.ProcessListTool;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.attachment.AttachmentReferences;
import com.pilot.server.attachment.ReadAttachmentTool;
import com.pilot.server.capability.LocalResourcesTool;
import com.pilot.server.compat.WorkspaceToolLookup;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.WebSearchProperties;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 按工作区构建工具集。
 *
 * <p>每个会话可以选择不同的本地目录，因此工具（尤其 {@link PathValidator}）必须按根目录隔离，
 * 不能做成全局单例，否则会话会互相越界。构建结果按规范化路径缓存。
 */
@Component
@RequiredArgsConstructor
public class WorkspaceToolFactory implements WorkspaceToolLookup {
    private static final Duration WEB_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final AgentProperties properties;
    private final WebSearchProperties webSearchProperties;
    private final SnapshotSink snapshots;
    private final ObjectMapper mapper;
    private final HttpClient webClient =
        HttpClient.newBuilder().connectTimeout(WEB_CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ConcurrentMap<String, ToolRegistry> cache = new ConcurrentHashMap<>();
    /** 可选能力：测试里手工构造本工厂时不注入，对应工具就不存在。 */
    private BrowserBackend browser;
    private McpManager mcp;
    private Selection execution;
    private ObjectProvider<AttachmentReferences> attachmentReferences;
    private QuestionBroker questions;
    /** 建工具表时读取已经落库的计划，避免把完成的步骤退回去。 */
    private final ThreadLocal<Supplier<List<PlanItem>>> plans = ThreadLocal.withInitial(() -> List::of);
    /** 建工具表时绑定当前会话，让 ask_user 记录等待归属。 */
    private final ThreadLocal<String> currentSession = ThreadLocal.withInitial(() -> "");

    @Autowired(required = false)
    public void setExecution(Selection execution) {
        this.execution = execution;
    }

    public Selection executionSelection() {
        return execution;
    }

    @Autowired(required = false)
    public void setBrowser(BrowserBackend browser) {
        this.browser = browser;
    }

    @Autowired(required = false)
    public void setMcp(McpManager mcp) {
        this.mcp = mcp;
    }

    @Autowired(required = false)
    public void setQuestions(QuestionBroker questions) {
        this.questions = questions;
    }

    @Autowired(required = false)
    public void setAttachmentReferences(ObjectProvider<AttachmentReferences> attachmentReferences) {
        this.attachmentReferences = attachmentReferences;
    }

    /**
     * 只在「某个会话的某次运行」里才有意义的外部能力：浏览器按会话隔离，MCP 按工作区查配置。
     * 工具本身始终注册，是否对模型可见由各自的 {@code availability()} 在运行时决定——
     * 浏览器没装好、没有配置 MCP 服务器时，模型看不到它们。
     */
    public List<Tool> sessionExtras(String sessionId, String workspace) {
        List<Tool> extras = new ArrayList<>();
        Path root = resolve(workspace);
        if (browser != null) {
            extras.add(new BrowserTool(browser, sessionId, new PathValidator(root)));
        }
        if (mcp != null) {
            extras.add(new McpToolsTool(mcp, root));
            extras.add(new McpCallTool(mcp, root));
        }
        // 运行中可能首次追加附件，分叉会话也可能仅引用父会话文件；授权在每次读取时检查。
        if (attachmentReferences != null) {
            extras.add(new ReadAttachmentTool(path -> attachmentReferences.getObject().resolvePath(sessionId, path)));
        }
        return extras;
    }

    /** @param workspace 会话工作区；为空时回退到配置中的默认工作区 */
    public ToolRegistry registryFor(String workspace) {
        Path root = resolve(workspace);
        String key = root.toString().toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(key, ignored -> create(root, null));
    }

    /**
     * 会话级工具集：额外注册 {@code update_plan}（以及调用方随后追加的 spawn_agent）。
     *
     * <p>刻意不缓存：工具实例会捕获本次 run 的事件出口，复用上一轮的工具会把事件发到旧的 run 上。
     * 每次运行新建若干无状态对象，开销可以忽略。
     */
    public ToolRegistry registryFor(String workspace, PlanSink planSink) {
        return create(resolve(workspace), planSink, List.of(), "");
    }

    /** @param extraRoots 项目关联的其他文件夹：绝对路径可读写，相对路径仍按默认工作区 */
    public ToolRegistry registryFor(String workspace, List<Path> extraRoots, PlanSink planSink) {
        return create(resolve(workspace), planSink, extraRoots, currentSession.get());
    }

    /** 带会话 ID 的工具集，让 ask_user 等交互等待与具体会话绑定。 */
    public ToolRegistry registryFor(String workspace, List<Path> extraRoots, PlanSink planSink, String sessionId) {
        return create(resolve(workspace), planSink, extraRoots, sessionId);
    }

    /** 只在构建这一次工具表时有效，用完要清掉。 */
    public void usePlan(Supplier<List<PlanItem>> current) {
        if (current == null) {
            plans.remove();
        } else {
            plans.set(current);
        }
    }

    /** 只在构建这一次工具表时有效，用完要清掉。 */
    public void useSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            currentSession.remove();
        } else {
            currentSession.set(sessionId.strip());
        }
    }

    public ToolRegistry defaultRegistry() {
        return registryFor(properties.workspace());
    }

    public Path resolve(String workspace) {
        String target = workspace == null || workspace.isBlank() ? properties.workspace() : workspace;
        return Path.of(target).toAbsolutePath().normalize();
    }

    /**
     * 当前工作区已注册的工具名，供诊断判断「钩子的 matcher 还命中得了谁」。
     *
     * <p>取不到就返回空集：诊断宁可显示「无法判断」，也不能因为这里抛异常把整次体检打挂。
     */
    @Override
    public java.util.Set<String> toolNames(String workspace) {
        try {
            return java.util.Set.copyOf(registryFor(workspace).names());
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    /**
     * 子 Agent 的工具集：文件视图按 {@link AgentScope} 裁剪，写工具替换为沙箱版。
     *
     * <p>只有真正注册进来的工具模型才看得见：白名单外的工具不是「拒绝调用」，而是根本不存在，
     * 这样模型不会反复尝试越权。写工具即使注册，也只会把改动提交到沙箱。
     *
     * @param sandbox 改动出口；只读任务传 {@link SandboxSink#rejectAll()} 也不会被触发
     */
    public ToolRegistry registryForScope(String workspace, AgentScope scope, SandboxSink sandbox) {
        return registryForScope(workspace, scope, sandbox, List.of());
    }

    /**
     * @param extraRoots 父会话所属项目绑定的其他文件夹；子 Agent 能访问的目录与父会话一致，
     *                   但写权限仍由 {@link AgentScope} 限定，不因为多看几个目录而变大
     */

    /** @param extraRoots 项目关联的其他文件夹；子 Agent 白名单里没有它们，仍只看默认工作区 */
    public ToolRegistry registryForScope(String workspace, AgentScope scope, SandboxSink sandbox,
        List<Path> extraRoots) {
        AgentScope view = scope == null ? AgentScope.readOnly(List.of(), List.of()) : scope;
        PathValidator validator = new PathValidator(resolve(workspace), extraRoots, view);
        ToolRegistry registry = new ToolRegistry();
        // 每个子 Agent 一份「先读后改」记录，与父级互不共享
        ReadTracker reads = new ReadTracker();
        if (view.allowsTool(ToolNames.READ_FILE)) {
            registry.register(new ReadFileTool(validator, reads, sandbox));
        }
        if (view.allowsTool(ToolNames.LIST_DIR)) {
            registry.register(new ListDirTool(validator));
        }
        if (view.allowsTool(ToolNames.GLOB_FILES)) {
            registry.register(new GlobFilesTool(validator));
        }
        if (view.allowsTool(ToolNames.SEARCH_TEXT)) {
            registry.register(new SearchTool(validator, reads));
        }
        if (view.allowsTool(CodeSymbolTool.NAME)) {
            registry.register(new CodeSymbolTool(validator));
        }
        if (view.allowsTool(ToolNames.WEB_SEARCH)) {
            registry.register(searchTool());
        }
        if (view.allowsTool(ToolNames.WEB_FETCH)) {
            registry.register(new WebFetchTool(webClient));
        }
        // 只读任务默认不注册通用命令：命令白名单为空时 run_command 会退回内置可执行白名单，
        // 那已经超出「只读调研」的范围。需要跑命令的调研任务必须显式声明 commands。
        if ((view.writable() || !view.allowCommands().isEmpty()) && view.allowsTool(ToolNames.RUN_COMMAND)) {
            registry.register(new SandboxCommandTool(validator, sandbox, execution));
        }
        if (view.writable() && view.allowsTool(ToolNames.EDIT_FILE)) {
            registry.register(new SandboxEditFileTool(validator, sandbox, reads));
        }
        if (view.writable() && view.allowsTool(ToolNames.CREATE_FILE)) {
            registry.register(new SandboxCreateFileTool(validator, sandbox, reads));
        }
        return registry;
    }

    private WebSearchTool searchTool() {
        return new WebSearchTool(webClient, mapper, webSearchProperties.endpointUri(),
            webSearchProperties.requestTimeout());
    }

    private ToolRegistry create(Path root, PlanSink planSink) {
        return create(root, planSink, List.of(), "");
    }

    private ToolRegistry create(Path root, PlanSink planSink, List<Path> extraRoots, String sessionId) {
        PathValidator validator = new PathValidator(root, extraRoots);
        ToolRegistry registry = new ToolRegistry();
        // 每次 run 一份「先读后改」记录：read_file 登记过的文件才允许 edit_file
        // （刚创建或刚编辑过的文件同样登记，连续编辑不受影响）
        ReadTracker reads = new ReadTracker();
        registry.register(new ReadFileTool(validator, reads));
        registry.register(new ListDirTool(validator));
        registry.register(new GlobFilesTool(validator));
        registry.register(new SearchTool(validator, reads));
        registry.register(searchTool());
        registry.register(new WebFetchTool(webClient));
        registry.register(new EditFileTool(validator, snapshots, reads));
        registry.register(new CreateFileTool(validator, snapshots, reads));
        registry.register(new RememberTool(validator, snapshots));
        registry.register(new OfficeTool(validator, snapshots));
        registry.register(new PrepareRuntimeTool(validator));
        registry.register(new LocalResourcesTool());
        registry.register(new ApplyPatchTool(validator, snapshots, reads));
        registry.register(new RunCommandTool(validator, commandChanges()));
        registry.register(new ProcessListTool());
        if (planSink != null) {
            registry.register(new UpdatePlanTool(planSink, plans.get()));
        }
        LocalCodeChecker code = LocalCodeChecker.installed(root);
        registry.register(new CodeDiagnosticsTool(() -> code.available() ? "ready" : "", code::diagnose));
        registry.register(new CodeSymbolTool(validator));
        if (questions != null) {
            registry.register(new AskUserTool(questions, sessionId));
        }
        registry.register(new EnableToolsTool(registry));
        return registry;
    }

    private CommandChangeTracker commandChanges() {
        return snapshots instanceof CommandChangeTracker tracker ? tracker : CommandChangeTracker.none();
    }
}
