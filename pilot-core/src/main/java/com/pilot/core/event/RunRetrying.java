package com.pilot.core.event;

/**
 * 模型请求暂时失败，运行没有结束，等一会儿再试。
 *
 * @param attempt 第几次重试，从 1 开始
 * @param delayMs 这次等待多久再发下一次请求
 * @param reason  失败原因，给界面看的短句
 */
public record RunRetrying(int attempt, long delayMs, String reason) implements SessionEvent {
    public RunRetrying {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0");
        }
        reason = reason == null ? "" : reason;
    }

    @Override
    public EventType type() {
        return EventType.RUN_RETRYING;
    }
}
