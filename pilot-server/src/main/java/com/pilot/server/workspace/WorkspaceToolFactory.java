package com.pilot.server.workspace;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.tool.CreateFileTool;
import com.pilot.core.tool.EditFileTool;
import com.pilot.core.tool.ListDirTool;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.RunCommandTool;
import com.pilot.core.tool.SandboxCreateFileTool;
import com.pilot.core.tool.SandboxEditFileTool;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.SearchTool;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UpdatePlanTool;
import com.pilot.core.tool.WebFetchTool;
import com.pilot.core.tool.WebSearchTool;
import com.pilot.core.workspace.PathValidator;
import tools.jackson.databind.ObjectMapper;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.WebSearchProperties;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private final HttpClient webClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ConcurrentMap<String, ToolRegistry> cache = new ConcurrentHashMap<>();

    public WorkspaceToolFactory(AgentProperties properties, WebSearchProperties webSearchProperties,
                                SnapshotSink snapshots, ObjectMapper mapper) {
        this.properties = properties;
        this.webSearchProperties = webSearchProperties;
        this.snapshots = snapshots;
        this.mapper = mapper;
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
     * 每次运行新建 7 个无状态对象，开销可以忽略。
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
        AgentScope view = scope == null ? AgentScope.readOnly(java.util.List.of(), java.util.List.of()) : scope;
        var validator = new PathValidator(resolve(workspace), view);
        var registry = new ToolRegistry();
        if (view.allowsTool("read_file")) {
            registry.register(new ReadFileTool(validator));
        }
        if (view.allowsTool("list_dir")) {
            registry.register(new ListDirTool(validator));
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
        if (view.allowsTool("run_command")) {
            registry.register(new RunCommandTool(validator));
        }
        if (view.writable() && view.allowsTool("edit_file")) {
            registry.register(new SandboxEditFileTool(validator, sandbox));
        }
        if (view.writable() && view.allowsTool("create_file")) {
            registry.register(new SandboxCreateFileTool(validator, sandbox));
        }
        return registry;
    }

    private WebSearchTool searchTool() {
        return new WebSearchTool(webClient, mapper, webSearchProperties.endpointUri());
    }

    private ToolRegistry create(Path root, PlanSink planSink) {
        var validator = new PathValidator(root);
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool(validator));
        registry.register(new ListDirTool(validator));
        registry.register(new SearchTool(validator));
        registry.register(searchTool());
        registry.register(new WebFetchTool(webClient));
        registry.register(new EditFileTool(validator, snapshots));
        registry.register(new CreateFileTool(validator, snapshots));
        registry.register(new RunCommandTool(validator));
        if (planSink != null) {
            registry.register(new UpdatePlanTool(planSink));
        }
        return registry;
    }
}
