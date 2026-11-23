package com.pilot.core.coordination;

/** 协作请求的状态：只有目标会话能接受或拒绝，只有发起会话能撤销，超时自动变为 {@code EXPIRED}。 */
public enum CoordinationRequestStatus {
    PENDING, ACCEPTED, DECLINED, WITHDRAWN, EXPIRED;

    public boolean open() {
        return this == PENDING;
    }
}
