package com.pilot.core.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 审批结果，与 {@link ApprovalRequired} 通过 callId 关联。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalResolved(String callId, ApprovalDecision decision, String reason) implements SessionEvent {
    public ApprovalResolved(String callId, ApprovalDecision decision) {
        this(callId, decision, null);
    }

    @Override
    public EventType type() {
        return EventType.APPROVAL_RESOLVED;
    }
}
