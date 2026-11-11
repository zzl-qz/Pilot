package com.pilot.core.session;

public record EventStreamLimits(int queueItems, long queueBytes, long totalBytes, int subscribers,
    int subscribersPerSession, int pageSize, long queueWaitMillis, long sendMillis, long replayMillis,
    long referenceBytes) {
    public EventStreamLimits {
        if (queueItems < 1 || queueBytes < 1024 || totalBytes < queueBytes || subscribers < 1
            || subscribersPerSession < 1 || pageSize < 1 || pageSize > 512 || queueWaitMillis < 1 || sendMillis < 1
            || replayMillis < 1 || referenceBytes < 512) {
            throw new IllegalArgumentException("事件流预算无效");
        }
    }

    public static EventStreamLimits defaults() {
        return new EventStreamLimits(4096, 8L * 1024 * 1024, 128L * 1024 * 1024, 256, 32, 256, 30_000, 30_000, 300_000,
            256 * 1024);
    }
}
