package com.pilot.core.event;

/** 需要用户审批后才能继续执行的高风险工具调用。 */
public record ApprovalRequired(String callId, String name, String argsSummary) implements SessionEvent {
    @Override
    public EventType type() {
        return EventType.APPROVAL_REQUIRED;
    }
}
