package com.pilot.core.event;

/** 明确区分用户取消与其他失败的终止事件。 */
public record RunCancelled(String reason) implements SessionEvent {
    public RunCancelled {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.strip();
    }

    @Override
    public EventType type() {
        return EventType.RUN_CANCELLED;
    }
}
