package com.pilot.server.controller;

import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpServerConfig;
import com.pilot.core.mcp.McpToolInfo;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.PermissionSettings;
import com.pilot.server.workspace.WorkspaceToolFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 能力清单：这个会话现在能用哪些工具、哪些 MCP 服务器、跑在什么模型与权限档位下。
 *
 * <p>「能力」页签之前是一段写死的说明，和实际注册表没关系；这里把注册表与 MCP 配置读出来，
 * 让用户看到真正生效的能力，以及不可用时明确的原因（例如浏览器运行时没装）。
 * 技能清单走 {@code /api/skills}，界面自己合并，避免两处分别解析目录。
 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class CapabilitiesController {
    private static final int MAX_DESCRIPTION_CHARS = 180;

    private final SessionService sessions;
    private final AgentProperties agentProperties;
    private final PermissionSettings permissionSettings;
    private final ModelSettings modelSettings;
    private final ObjectProvider<ToolRegistry> toolRegistry;
    private final ObjectProvider<WorkspaceToolFactory> toolFactory;
    private final ObjectProvider<McpManager> mcpManager;

    public CapabilitiesController(SessionService sessions, AgentProperties agentProperties,
                                  PermissionSettings permissionSettings, ModelSettings modelSettings,
                                  ObjectProvider<ToolRegistry> toolRegistry,
                                  ObjectProvider<WorkspaceToolFactory> toolFactory,
                                  ObjectProvider<McpManager> mcpManager) {
        this.sessions = sessions;
        this.agentProperties = agentProperties;
        this.permissionSettings = permissionSettings;
        this.modelSettings = modelSettings;
        this.toolRegistry = toolRegistry;
        this.toolFactory = toolFactory;
        this.mcpManager = mcpManager;
    }

    @GetMapping("/capabilities")
    public CapabilitiesResponse capabilities(@PathVariable String id) {
        Session session = sessions.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
        String workspace = session.workspace() == null || session.workspace().isBlank()
                ? agentProperties.workspace()
                : session.workspace();
        Path root = Path.of(workspace);

        return new CapabilitiesResponse(root.toString(), modelSettings.snapshot().model(),
                permissionSettings.mode().name(), tools(id, workspace), servers(root));
    }

    /**
     * 工具清单要和模型真正拿到的一致：默认注册表之外，还有按会话装配的浏览器与 MCP 工具
     * （{@link WorkspaceToolFactory#sessionExtras}），否则能力页签里看不到最显眼的 browser。
     */
    private List<ToolPayload> tools(String sessionId, String workspace) {
        ArrayList<Tool> all = new ArrayList<>();
        ToolRegistry registry = toolRegistry.getIfAvailable();
        if (registry != null) {
            all.addAll(registry.tools());
        }
        WorkspaceToolFactory factory = toolFactory.getIfAvailable();
        if (factory != null) {
            all.addAll(factory.sessionExtras(sessionId, workspace));
        }
        ArrayList<ToolPayload> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Tool tool : all) {
            if (seen.add(tool.name())) {
                result.add(payload(tool));
            }
        }
        return List.copyOf(result);
    }

    /**
     * MCP 服务器只列配置与「是否已在运行」：罗列工具不会为了看一眼而拉起子进程，
     * 启动 MCP 等于执行配置里的命令，必须由模型真正调用时才发生。
     */
    private List<McpServerPayload> servers(Path workspace) {
        McpManager manager = mcpManager.getIfAvailable();
        if (manager == null) {
            return List.of();
        }
        Map<String, McpServerConfig> configured = manager.servers(workspace);
        ArrayList<McpServerPayload> result = new ArrayList<>();
        for (McpServerConfig config : configured.values()) {
            boolean running = manager.running(workspace, config.name());
            List<McpToolInfo> known = manager.knownTools(workspace, config.name());
            result.add(new McpServerPayload(config.name(), commandLine(config), config.usable(),
                    running, manager.failure(workspace, config.name()), config.unsupported(),
                    known.stream().map(McpToolInfo::name).toList()));
        }
        return List.copyOf(result);
    }

    private static String commandLine(McpServerConfig config) {
        if (config.command() == null) {
            return "";
        }
        return config.args().isEmpty()
                ? config.command()
                : config.command() + " " + String.join(" ", config.args());
    }

    private static ToolPayload payload(Tool tool) {
        Availability availability;
        try {
            availability = tool.availability();
        } catch (RuntimeException e) {
            availability = Availability.unavailable("能力探测失败：" + e.getMessage());
        }
        if (availability == null) {
            availability = Availability.ready();
        }
        return new ToolPayload(tool.name(), summarize(tool.description()), tool.readOnly(),
                availability.available(), availability.reason());
    }

    /** 工具描述是写给模型看的多行 Markdown，界面上只留第一句有用的内容。 */
    private static String summarize(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String raw : description.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("```")) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(line);
            if (text.length() >= MAX_DESCRIPTION_CHARS) {
                break;
            }
        }
        String value = text.toString();
        return value.length() > MAX_DESCRIPTION_CHARS
                ? value.substring(0, MAX_DESCRIPTION_CHARS) + "…"
                : value;
    }

    /** @param reason 不可用时给用户看的修复提示，可用时为空字符串 */
    public record ToolPayload(String name, String description, boolean readOnly,
                              boolean available, String reason) {
    }

    /** @param tools 已罗列的工具名；服务器没启动时为空（不会为了看清单而拉起进程） */
    public record McpServerPayload(String name, String command, boolean usable, boolean running,
                                   String failure, String unsupported, List<String> tools) {
    }

    public record CapabilitiesResponse(String workspace, String model, String permissionMode,
                                       List<ToolPayload> tools, List<McpServerPayload> mcpServers) {
    }
}
