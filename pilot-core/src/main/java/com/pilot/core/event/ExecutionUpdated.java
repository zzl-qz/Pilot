package com.pilot.core.event;

/** Task assessment and verification progress, separate from multi-agent DAG events. */
public record ExecutionUpdated(String mode, String phase, int requests, String reason, String gap) implements SessionEvent {
    @Override public EventType type() { return EventType.EXECUTION_UPDATED; }
}
