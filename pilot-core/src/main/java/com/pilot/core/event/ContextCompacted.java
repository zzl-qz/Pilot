package com.pilot.core.event;

/**
 * 上下文压缩：早期消息被折叠成一份交接摘要。
 *
 * @param coveredMessages 被折叠的消息条数
 * @param charactersBefore 压缩前的字符数
 * @param charactersAfter  压缩后的字符数
 * @param summary          摘要正文（前端默认折叠展示）
 */
public record ContextCompacted(int coveredMessages, int charactersBefore, int charactersAfter, String summary)
        implements SessionEvent {
    public ContextCompacted {
        if (coveredMessages < 0) {
            throw new IllegalArgumentException("coveredMessages must be >= 0");
        }
        if (charactersBefore < 0 || charactersAfter < 0) {
            throw new IllegalArgumentException("character counts must be >= 0");
        }
        summary = summary == null ? "" : summary;
    }

    @Override
    public EventType type() {
        return EventType.CONTEXT_COMPACTED;
    }
}
