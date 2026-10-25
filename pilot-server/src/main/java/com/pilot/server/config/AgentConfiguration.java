package com.pilot.server.config;

import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.workspace.WorkspaceToolFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** real 模式下的工具装配；工具集按会话工作区由 {@link WorkspaceToolFactory} 构建。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pilot.agent", name = "mode", havingValue = "real")
public class AgentConfiguration {
    /** 默认工作区的工具集，供 /api/health 展示可用工具。 */
    @Bean
    ToolRegistry toolRegistry(WorkspaceToolFactory factory) {
        return factory.defaultRegistry();
    }
}
