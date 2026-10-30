package com.pilot.server.mcp;

import com.pilot.core.mcp.McpManager;
import com.pilot.server.config.PilotDataDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 服务器管理。配置来自工作区的 {@code .mcp.json}，以及数据目录下的用户级 {@code mcp.json}。
 * 子进程的 PATH 前面追加内置 Node 目录，{@code npx} 类服务器不依赖用户自己装 Node。
 */
@Configuration
public class McpConfiguration {

    @Bean(destroyMethod = "close")
    public McpManager mcpManager(ObjectMapper mapper, PilotDataDir dataDir) {
        return new McpManager(mapper, dataDir.file("mcp.json"), dataDir.file("mcp-logs"),
                () -> System.getProperty("pilot.runtime.bin", ""));
    }
}
