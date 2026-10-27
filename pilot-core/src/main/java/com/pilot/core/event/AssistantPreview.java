package com.pilot.core.event;

/**
 * 模型正在往外吐的文字。只推给已经连着的界面，不写入会话历史。
 * 这一次请求成功后，会再落一条完整的 {@link AssistantDelta}。
 */
public record AssistantPreview(String text) implements SessionEvent {
    public AssistantPreview {
        text = text == null ? "" : text;
    }

    @Override
    public EventType type() {
        return EventType.ASSISTANT_PREVIEW;
    }
}
