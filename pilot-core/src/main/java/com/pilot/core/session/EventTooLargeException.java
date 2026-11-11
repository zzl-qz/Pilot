package com.pilot.core.session;

public final class EventTooLargeException extends RuntimeException {
    public EventTooLargeException() {
        super("单条历史事件超过 8 MiB，无法自动恢复，请检查事件数据");
    }
}
