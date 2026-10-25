package com.pilot.server.config;

import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.ReadOnlyPermissionGate;
import com.pilot.core.tool.ListDirTool;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.SearchTool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.workspace.PathValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** real 模式下的只读工具装配。mock 模式不创建这些 Bean。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pilot.agent", name = "mode", havingValue = "real")
public class AgentConfiguration {
    @Bean
    PathValidator pathValidator(AgentProperties properties) {
        return new PathValidator(Path.of(properties.workspace()));
    }

    @Bean
    ToolRegistry toolRegistry(PathValidator pathValidator) {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool(pathValidator));
        registry.register(new ListDirTool(pathValidator));
        registry.register(new SearchTool(pathValidator));
        return registry;
    }

    @Bean
    PermissionGate permissionGate() {
        return new ReadOnlyPermissionGate();
    }
}
