package com.pilot.server.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpTrustStore;
import com.pilot.core.runtime.bootstrap.RuntimeEnvironment;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.databind.ObjectMapper;

/**
 * MCP 服务器管理。配置来自工作区的 {@code .mcp.json}，以及数据目录下的用户级 {@code mcp.json}。
 * 子进程的 PATH 前面追加内置 Node 目录，{@code npx} 类服务器不依赖用户自己装 Node。
 *
 * <p>信任记录（{@code mcp-trust.json}）记录用户「批准并信任」过的启动命令与工具定义哈希：
 * 免审批只发生在信任记录与当前配置匹配且工具哈希未漂移时，见 McpTrustPolicy。
 */
@Configuration
public class McpConfiguration {

    @Bean(destroyMethod = "close")
    public McpManager mcpManager(ObjectMapper mapper, PilotDataDir dataDir) {
        McpTrustStore trustStore = new McpTrustStore(mapper, dataDir.file("mcp-trust.json"));
        return new McpManager(mapper, dataDir.file("mcp.json"), dataDir.file("mcp-logs"),
            RuntimeEnvironment::bundledBinPath, trustStore);
    }
}
