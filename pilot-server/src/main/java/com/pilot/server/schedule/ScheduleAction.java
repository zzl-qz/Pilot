package com.pilot.server.schedule;

/** 计划控制接口接受的操作。请求里是小写单词，大小写必须完全一致。 */
enum ScheduleAction {
    CANCEL("cancel"), PAUSE("pause"), RESUME("resume"), RESCHEDULE("reschedule");

    private final String wire;

    ScheduleAction(String wire) {
        this.wire = wire;
    }

    /** 不认识的操作（含 null）统一按参数错误拒绝。 */
    static ScheduleAction parse(String action) {
        for (ScheduleAction candidate : values()) {
            if (candidate.wire.equals(action)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("不支持的操作");
    }
}
