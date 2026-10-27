package com.pilot.core.multi;

/** 沙箱改动的审阅状态：待人工审批 / 已接受（已落盘）/ 已拒绝。 */
public enum ChangeStatus {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    private final String wireName;

    ChangeStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
