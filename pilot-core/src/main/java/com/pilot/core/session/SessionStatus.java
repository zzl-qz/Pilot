package com.pilot.core.session;

/** 会话状态。终态不可再接收新的运行事件。 */
public enum SessionStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }
}
