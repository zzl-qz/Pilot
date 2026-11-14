package com.pilot.core.event;

/** 运行失败。code 供客户端区分处理，message 面向用户。 */
public record RunFailed(String code, String message, boolean retryable) implements SessionEvent {
    public RunFailed(RunFailureCode code, String message, boolean retryable) {
        this(code.name(), message, retryable);
    }

    @Override
    public EventType type() {
        return EventType.RUN_FAILED;
    }
}
