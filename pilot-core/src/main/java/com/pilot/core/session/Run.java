package com.pilot.core.session;

import java.time.Instant;
import java.util.Objects;

/** 一次运行的不可变快照；运行进度通过带相同 runId 的事件观察。 */
public record Run(String runId, String sessionId, Instant startedAt) {
    public Run {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
