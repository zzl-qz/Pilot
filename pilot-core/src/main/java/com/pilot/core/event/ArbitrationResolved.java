package com.pilot.core.event;

/**
 * 人工仲裁结果回执。
 *
 * @param arbitrationId 对应的仲裁标识
 * @param choice        用户选择的操作
 */
public record ArbitrationResolved(String arbitrationId, String choice) implements SessionEvent {
    public ArbitrationResolved {
        arbitrationId = arbitrationId == null ? "" : arbitrationId;
        choice = choice == null ? "" : choice;
    }

    @Override
    public EventType type() {
        return EventType.ARBITRATION_RESOLVED;
    }
}
