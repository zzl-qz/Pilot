package com.pilot.core.event;

import java.util.List;

import com.pilot.core.message.Attachment;

/** 当前运行在安全边界消费的引导消息，包含原始图片与位置。 */
public record RunSteered(String input, List<Attachment> attachments,
    List<Integer> imagePositions) implements SessionEvent {
    public RunSteered(String input) {
        this(input, List.of(), List.of());
    }

    public RunSteered {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        imagePositions = imagePositions == null ? List.of() : List.copyOf(imagePositions);
        input = input == null ? "" : input.strip();
        if (input.isBlank() && attachments.isEmpty()) {
            throw new IllegalArgumentException("input must not be blank");
        }
    }

    @Override
    public EventType type() {
        return EventType.RUN_STEERED;
    }
}
