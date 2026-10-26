package com.pilot.core.event;

/**
 * 运行中追加的用户输入被消费。
 *
 * @param input 追加的指令原文
 */
public record RunSteered(String input) implements SessionEvent {
    public RunSteered {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        input = input.strip();
    }

    @Override
    public EventType type() {
        return EventType.RUN_STEERED;
    }
}
