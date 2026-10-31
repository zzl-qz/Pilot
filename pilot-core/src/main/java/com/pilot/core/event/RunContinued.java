package com.pilot.core.event;

/**
 * 自动续跑：上一轮因步数或预算触顶结束，满足继续条件后接上下一轮。
 *
 * @param attempt     当前是第几次自动续跑（从 1 开始）
 * @param maxAttempts 允许的最大自动续跑次数
 * @param reason      触发原因
 */
public record RunContinued(int attempt, int maxAttempts, String reason) implements SessionEvent {
    public RunContinued {
        if (attempt < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("attempt and maxAttempts must be >= 1");
        }
        reason = reason == null ? "" : reason;
    }

    @Override
    public EventType type() {
        return EventType.RUN_CONTINUED;
    }
}
