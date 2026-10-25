package com.pilot.server.controller;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运行状态：让前端能显示当前是 Mock 剧本还是真实模型，以及可用的只读工具。 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final AgentProperties agentProperties;
    private final ModelProperties modelProperties;
    private final ObjectProvider<ToolRegistry> toolRegistry;

    public HealthController(AgentProperties agentProperties, ModelProperties modelProperties,
                            ObjectProvider<ToolRegistry> toolRegistry) {
        this.agentProperties = agentProperties;
        this.modelProperties = modelProperties;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public HealthResponse health() {
        ToolRegistry registry = toolRegistry.getIfAvailable();
        List<String> tools = registry == null
                ? List.of()
                : registry.tools().stream().map(Tool::name).toList();
        return new HealthResponse("ok", agentProperties.mode(), modelProperties.model(), tools,
                agentProperties.permissionMode().name(), agentProperties.workspace());
    }

    public record HealthResponse(String status, String agentMode, String model, List<String> tools,
                                String permissionMode, String workspace) { }
}
