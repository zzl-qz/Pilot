package com.pilot.core.event;

/**
 * 模型正在吐的推理。只推给已经连着的界面，不写入会话历史。
 * 回答正文仍走 {@link AssistantPreview} / {@link AssistantDelta}。
 */
public record AssistantReasoning(String text) implements SessionEvent {
    public AssistantReasoning {
        text = text == null ? "" : text;
    }

    @Override
    public EventType type() {
        return EventType.ASSISTANT_REASONING;
    }
}
