package com.pilot.core.mcp;

/** MCP 服务器启动、通信或调用失败；消息面向模型。 */
public class McpException extends RuntimeException {
    private final boolean outcomeUnknown;

    public McpException(String message) {
        this(message, false);
    }

    public McpException(String message, boolean outcomeUnknown) {
        super(message);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
