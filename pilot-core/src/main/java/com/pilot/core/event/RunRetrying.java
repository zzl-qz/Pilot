package com.pilot.core.event;

/**
 * 模型请求暂时失败，运行没有结束，等一会儿再试。
 *
 * @param attempt     第几次重试，从 1 开始
 * @param delayMs     这次等待多久再发下一次请求
 * @param reason      失败原因，给界面看的短句
 * @param elapsedMs   从第一次失败起已经等/试了多久；未知为 {@code null}
 * @param remainingMs 重试预算剩余毫秒，{@code -1} 表示不设上限；未知为 {@code null}
 * @param maxAttempts 重试预算的最大次数；未知为 {@code null}
 */
public record RunRetrying(int attempt, long delayMs, String reason, Long elapsedMs, Long remainingMs,
    Integer maxAttempts) implements SessionEvent {
    public RunRetrying {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0");
        }
        reason = reason == null ? "" : reason;
    }

    /** 兼容旧调用：不携带预算信息。 */
    public RunRetrying(int attempt, long delayMs, String reason) {
        this(attempt, delayMs, reason, null, null, null);
    }

    @Override
    public EventType type() {
        return EventType.RUN_RETRYING;
    }
}
