package com.pilot.server.workspace;

import com.pilot.core.tool.CreateFileTool;
import com.pilot.core.tool.EditFileTool;
import com.pilot.core.tool.ListDirTool;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.RunCommandTool;
import com.pilot.core.tool.SearchTool;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
@ConditionalOnProperty(prefix = "pilot.agent", name = "mode", havingValue = "real")
public class WorkspaceToolFactory {
    private final AgentProperties properties;
    private final SnapshotSink snapshots;
    private final ConcurrentMap<String, ToolRegistry> cache = new ConcurrentHashMap<>();

    public WorkspaceToolFactory(AgentProperties properties, SnapshotSink snapshots) {
        this.properties = properties;
        this.snapshots = snapshots;
    }

    /** @param workspace 会话工作区；为空时回退到配置中的默认工作区 */
    public ToolRegistry registryFor(String workspace) {
        Path root = resolve(workspace);
        String key = root.toString().toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(key, ignored -> create(root));
    }

    public ToolRegistry defaultRegistry() {
        return registryFor(properties.workspace());
    }

    public Path resolve(String workspace) {
        String target = workspace == null || workspace.isBlank() ? properties.workspace() : workspace;
        return Path.of(target).toAbsolutePath().normalize();
    }

    private ToolRegistry create(Path root) {
        var validator = new PathValidator(root);
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool(validator));
        registry.register(new ListDirTool(validator));
        registry.register(new SearchTool(validator));
        registry.register(new EditFileTool(validator, snapshots));
        registry.register(new CreateFileTool(validator, snapshots));
        registry.register(new RunCommandTool(validator));
        return registry;
    }
}
