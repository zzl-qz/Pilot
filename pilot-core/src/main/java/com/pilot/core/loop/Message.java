package com.pilot.core.loop;

import com.pilot.core.tool.ToolCall;

import java.util.List;

/**
 * 模型对话消息，对应 OpenAI 兼容协议的 messages 元素。
 *
 * @param role       system / user / assistant / tool
 * @param content    文本内容；assistant 只发工具调用时可为空
 * @param toolCalls  assistant 消息请求的工具调用
 * @param toolCallId tool 消息对应的调用 ID
 */
public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public Message {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool message requires toolCallId");
        }
    }

    public Message(String role, String content) {
        this(role, content, List.of(), null);
    }

    public static Message system(String text) {
        return new Message("system", text);
    }

    public static Message user(String text) {
        return new Message("user", text);
    }

    public static Message assistant(String text, List<ToolCall> toolCalls) {
        return new Message("assistant", text, toolCalls, null);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, List.of(), toolCallId);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
