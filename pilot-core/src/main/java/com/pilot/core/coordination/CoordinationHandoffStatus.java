package com.pilot.core.coordination;

/** 交接状态：目标会话接受或拒绝，超过期限后变成 {@code EXPIRED}。 */
public enum CoordinationHandoffStatus {
    PENDING, ACCEPTED, DECLINED, EXPIRED;

    public boolean open() {
        return this == PENDING;
    }
}
