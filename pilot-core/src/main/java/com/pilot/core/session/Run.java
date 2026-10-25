package com.pilot.core.session;

import java.time.Instant;

/** 一次运行的标识。运行进度通过带相同 runId 的事件观察。 */
public record Run(String runId, String sessionId, Instant startedAt) {
}
