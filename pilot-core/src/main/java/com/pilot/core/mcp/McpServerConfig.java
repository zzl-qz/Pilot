package com.pilot.core.mcp;

import java.util.List;
import java.util.Map;

/**
 * 一个 MCP 服务器的启动配置（stdio 传输）。
 *
 * @param unsupported 非空表示配置存在但当前无法使用（例如只给了 HTTP url），原因会原样展示给模型
 */
public record McpServerConfig(String name, String command, List<String> args, Map<String, String> env,
                              String unsupported) {
    public McpServerConfig {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    public boolean usable() {
        return unsupported == null && command != null && !command.isBlank();
    }
}
