package com.pilot.core.event;

/**
 * 模型正在往外吐的文字。只推给已经连着的界面，不写入会话历史。
 * 这一次请求成功后，会再落一条完整的 {@link AssistantDelta}。
 *
 * @param reset 丢弃界面上的预览内容，从空白重新开始。请求重试或被判定为复读时使用：
 *              上一次尝试已经推出去的字作废，后续增量不能和它拼在一起。
 */
public record AssistantPreview(String text, boolean reset) implements SessionEvent {
    public AssistantPreview {
        text = text == null ? "" : text;
    }

    /** 兼容普通增量预览。 */
    public AssistantPreview(String text) {
        this(text, false);
    }

    /** 请求重试 / 输出作废时，清空界面上已经显示的预览。 */
    public static AssistantPreview discarded() {
        return new AssistantPreview("", true);
    }

    @Override
    public EventType type() {
        return EventType.ASSISTANT_PREVIEW;
    }
}
