package com.pilot.core.event;

/** 助手文本增量。客户端按序号拼接，不假设每段是完整句子。 */
public record AssistantDelta(String text) implements SessionEvent {
    @Override
    public EventType type() {
        return EventType.ASSISTANT_DELTA;
    }
}
