package com.pilot.core.event;

/** 工具开始执行。callId 用于关联同一工具调用的后续事件。 */
public record ToolStarted(String callId, String name, String summary) implements SessionEvent {
    @Override
    public EventType type() {
        return EventType.TOOL_STARTED;
    }
}
