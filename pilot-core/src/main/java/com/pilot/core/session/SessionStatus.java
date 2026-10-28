package com.pilot.core.session;

/** 会话最近一轮的状态；终态表示该轮已结束，仍可通过新一轮继续任务。 */
public enum SessionStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED,
    INTERRUPTED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == PAUSED || this == INTERRUPTED;
    }
}
