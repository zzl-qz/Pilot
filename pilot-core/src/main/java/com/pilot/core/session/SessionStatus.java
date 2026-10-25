package com.pilot.core.session;

/** 会话状态。终态为 {@link #COMPLETED}、{@link #FAILED}。 */
public enum SessionStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED
}
