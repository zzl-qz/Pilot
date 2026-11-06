package com.pilot.server.workspace;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserTool;
import com.pilot.core.exec.ExecutionBackends.Selection;
import com.pilot.core.mcp.McpCallTool;
import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpToolsTool;
import com.pilot.core.multi.AgentScope;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.tool.ApplyPatchTool;
import com.pilot.core.tool.CommandChangeTracker;
import com.pilot.core.tool.CreateFileTool;
import com.pilot.core.tool.EditFileTool;
import com.pilot.core.tool.GlobFilesTool;
import com.pilot.core.tool.ListDirTool;
import com.pilot.core.tool.OfficeTool;
import com.pilot.core.tool.PrepareRuntimeTool;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.RememberTool;
import com.pilot.core.tool.RunCommandTool;
import com.pilot.core.tool.SandboxCommandTool;
import com.pilot.core.tool.SandboxCreateFileTool;
import com.pilot.core.tool.SandboxEditFileTool;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.SearchTool;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UpdatePlanTool;
import com.pilot.core.tool.WebFetchTool;
import com.pilot.core.tool.WebSearchTool;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.capability.LocalResourcesTool;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.WebSearchProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * 按工作区构建工具集。
 *
 * <p>每个会话可以选择不同的本地目录，因此工具（尤其 {@link PathValidator}）必须按根目录隔离，
 * 不能做成全局单例，否则会话会互相越界。构建结果按规范化路径缓存。
 */
@Component
public class WorkspaceToolFactory {
    private final AgentProperties properties;
    private final WebSearchProperties webSearchProperties;
    private final SnapshotSink snapshots;
    private final ObjectMapper mapper;
    private final HttpClient webClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ConcurrentMap<String, ToolRegistry> cache = new ConcurrentHashMap<>();
    /** 可选能力：测试里手工构造本工厂时不注入，对应工具就不存在。 */
    private BrowserBackend browser;
    private McpManager mcp;
    private Selection execution;

    @Autowired(required = false)
    public void setExecution(Selection execution) {
        this.execution = execution;
    }

    public WorkspaceToolFactory(AgentProperties properties, WebSearchProperties webSearchProperties,
        SnapshotSink snapshots, ObjectMapper mapper) {
        this.properties = properties;
        this.webSearchProperties = webSearchProperties;
        this.snapshots = snapshots;
        this.mapper = mapper;
    }

    @Autowired(required = false)
    public void setBrowser(BrowserBackend browser) {
        this.browser = browser;
    }

    @Autowired(required = false)
    public void setMcp(McpManager mcp) {
        this.mcp = mcp;
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
        return create(resolve(workspace), planSink);
    }

    public ToolRegistry defaultRegistry() {
        return registryFor(properties.workspace());
    }

    public Path resolve(String workspace) {
        String target = workspace == null || workspace.isBlank() ? properties.workspace() : workspace;
        return Path.of(target).toAbsolutePath().normalize();
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
        AgentScope view = scope == null ? AgentScope.readOnly(List.of(), List.of()) : scope;
        PathValidator validator = new PathValidator(resolve(workspace), view);
        ToolRegistry registry = new ToolRegistry();
        // 每个子 Agent 一份「先读后改」记录，与父级互不共享
        ReadTracker reads = new ReadTracker();
        if (view.allowsTool("read_file")) {
            registry.register(new ReadFileTool(validator, reads, sandbox));
        }
        if (view.allowsTool("list_dir")) {
            registry.register(new ListDirTool(validator));
        }
        if (view.allowsTool("glob_files")) {
            registry.register(new GlobFilesTool(validator));
        }
        if (view.allowsTool("search_text")) {
            registry.register(new SearchTool(validator));
        }
        if (view.allowsTool("web_search")) {
            registry.register(searchTool());
        }
        if (view.allowsTool("web_fetch")) {
            registry.register(new WebFetchTool(webClient));
        }
        // 只读任务默认不注册通用命令：命令白名单为空时 run_command 会退回内置可执行白名单，
        // 那已经超出「只读调研」的范围。需要跑命令的调研任务必须显式声明 commands。
        if ((view.writable() || !view.allowCommands().isEmpty()) && view.allowsTool("run_command")) {
            registry.register(new SandboxCommandTool(validator, sandbox, execution));
        }
        if (view.writable() && view.allowsTool("edit_file")) {
            registry.register(new SandboxEditFileTool(validator, sandbox, reads));
        }
        if (view.writable() && view.allowsTool("create_file")) {
            registry.register(new SandboxCreateFileTool(validator, sandbox, reads));
        }
        return registry;
    }

    private WebSearchTool searchTool() {
        return new WebSearchTool(webClient, mapper, webSearchProperties.endpointUri());
    }

    private ToolRegistry create(Path root, PlanSink planSink) {
        PathValidator validator = new PathValidator(root);
        ToolRegistry registry = new ToolRegistry();
        // 每次 run 一份「先读后改」记录：read_file 登记过的文件才允许 edit_file
        // （刚创建或刚编辑过的文件同样登记，连续编辑不受影响）
        ReadTracker reads = new ReadTracker();
        registry.register(new ReadFileTool(validator, reads));
        registry.register(new ListDirTool(validator));
        registry.register(new GlobFilesTool(validator));
        registry.register(new SearchTool(validator));
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
        if (planSink != null) {
            registry.register(new UpdatePlanTool(planSink));
        }
        return registry;
    }

    private CommandChangeTracker commandChanges() {
        return snapshots instanceof CommandChangeTracker tracker ? tracker : CommandChangeTracker.none();
    }
}
