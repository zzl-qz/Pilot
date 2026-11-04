package com.pilot.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.tool.CommandTaskManager;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.workspace.WorkspaceToolFactory;

import tools.jackson.databind.ObjectMapper;

/** 工具装配。工具集按会话工作区由 {@link WorkspaceToolFactory} 构建。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebSearchProperties.class)
public class AgentConfiguration {
    @Bean(destroyMethod = "close")
    CommandTaskManager commandTaskManager(PilotDataDir data, ObjectMapper mapper) {
        return new CommandTaskManager(data.file("commands"), mapper);
    }

    /** 默认工作区的工具集，供 /api/health 展示可用工具。 */
    @Bean
    ToolRegistry toolRegistry(WorkspaceToolFactory factory) {
        return factory.defaultRegistry();
    }
}
