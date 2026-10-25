package com.pilot.core.event;

/** 审批结果，与 {@link ApprovalRequired} 通过 callId 关联。 */
public record ApprovalResolved(String callId, ApprovalDecision decision) implements SessionEvent {
    @Override
    public EventType type() {
        return EventType.APPROVAL_RESOLVED;
    }
}
