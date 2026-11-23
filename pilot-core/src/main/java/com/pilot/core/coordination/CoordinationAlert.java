package com.pilot.core.coordination;

import java.util.List;

/**
 * 协调看门狗发现的结构化风险告警：互等死锁（{@code DEADLOCK}）或等待目标疑似停滞/已终止（{@code STALLED_DEPENDENCY}）。
 */
public record CoordinationAlert(String kind, List<String> sessionIds, String summary) {
    public static final String DEADLOCK = "DEADLOCK";
    public static final String STALLED_DEPENDENCY = "STALLED_DEPENDENCY";

    public CoordinationAlert {
        kind = kind == null ? "" : kind.strip();
        sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
        summary = summary == null ? "" : summary.strip();
    }
}
