package com.pilot.core.coordination;

/** 协作请求的状态：只有目标会话能接受或拒绝，只有发起会话能撤销。 */
public enum CoordinationRequestStatus {
    PENDING, ACCEPTED, DECLINED, WITHDRAWN;

    public boolean open() {
        return this == PENDING;
    }
}
