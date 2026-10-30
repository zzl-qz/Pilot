package com.pilot.core.mcp;

/** MCP 服务器启动、通信或调用失败；消息面向模型。 */
public class McpException extends RuntimeException {
    public McpException(String message) {
        super(message);
    }
}
