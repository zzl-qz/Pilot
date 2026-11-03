package com.pilot.core.event;

import com.pilot.core.model.RequestReport;

public record ModelRequestCompleted(RequestReport report) implements SessionEvent {
    @Override public EventType type() { return EventType.MODEL_REQUEST_COMPLETED; }
}
