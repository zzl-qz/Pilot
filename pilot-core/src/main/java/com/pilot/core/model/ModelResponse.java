package com.pilot.core.model;

import com.pilot.core.tool.ToolCall;

import java.util.List;

public record ModelResponse(String content, List<ToolCall> toolCalls, ModelUsage usage,
                            FinishReason finishReason) {
    public enum FinishReason {
        STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, ERROR, UNKNOWN;

        public static FinishReason fromWire(String value) {
            if (value == null || value.isBlank()) return UNKNOWN;
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "stop" -> STOP;
                case "tool_calls", "function_call" -> TOOL_CALLS;
                case "length", "max_tokens" -> LENGTH;
                case "content_filter" -> CONTENT_FILTER;
                case "error" -> ERROR;
                default -> UNKNOWN;
            };
        }
    }

    public ModelResponse {
        usage = usage == null ? ModelUsage.unknown() : usage;
        finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
    }

    /** 兼容测试和第三方 ModelClient：没有 usage 时由 AgentLoop 做本地估算。 */
    public ModelResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, ModelUsage.unknown(), inferred(toolCalls));
    }

    public ModelResponse(String content, List<ToolCall> toolCalls, ModelUsage usage) {
        this(content, toolCalls, usage, inferred(toolCalls));
    }

    private static FinishReason inferred(List<ToolCall> calls) {
        return calls == null || calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
    }
}
