package com.pilot.server.controller;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.PermissionSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运行状态：当前模型、权限档位和可用工具。 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final AgentProperties agentProperties;
    private final ModelSettings modelSettings;
    private final PermissionSettings permissionSettings;
    private final ObjectProvider<ToolRegistry> toolRegistry;

    public HealthController(AgentProperties agentProperties, ModelSettings modelSettings,
                            PermissionSettings permissionSettings, ObjectProvider<ToolRegistry> toolRegistry) {
        this.agentProperties = agentProperties;
        this.modelSettings = modelSettings;
        this.permissionSettings = permissionSettings;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public HealthResponse health() {
        ToolRegistry registry = toolRegistry.getIfAvailable();
        List<String> tools = registry == null
                ? List.of()
                : registry.tools().stream().map(Tool::name).toList();
        return new HealthResponse("ok", modelSettings.snapshot().model(), tools,
                permissionSettings.mode().name(), agentProperties.workspace());
    }

    public record HealthResponse(String status, String model, List<String> tools,
                                String permissionMode, String workspace) { }
}
