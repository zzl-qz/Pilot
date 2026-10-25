package com.pilot.core.event;

/** 一次运行开始：携带触发这次运行的用户输入。 */
public record RunStarted(String input) implements SessionEvent {
    @Override
    public EventType type() {
        return EventType.RUN_STARTED;
    }
}
