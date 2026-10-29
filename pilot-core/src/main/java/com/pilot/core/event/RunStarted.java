package com.pilot.core.event;

/** 一次运行开始：携带触发这次运行的用户输入。 */
public record RunStarted(String input, java.util.List<com.pilot.core.loop.Attachment> attachments) implements SessionEvent {
    public RunStarted(String input) {
        this(input, java.util.List.of());
    }

    public RunStarted {
        attachments = attachments == null ? java.util.List.of() : java.util.List.copyOf(attachments);
    }

    @Override
    public EventType type() {
        return EventType.RUN_STARTED;
    }
}
