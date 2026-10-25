package com.pilot.core.event;

/** 工具执行结束，携带结果摘要与耗时。 */
public record ToolCompleted(String callId, String resultSummary, long latencyMs) implements SessionEvent {
    @Override
    public EventType type() {
        return EventType.TOOL_COMPLETED;
    }
}
