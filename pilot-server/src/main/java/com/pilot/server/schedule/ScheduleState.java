package com.pilot.server.schedule;

import com.pilot.core.util.WireEnum;

/** 一次性计划的状态。名字就是落库和接口里的字符串，前端按同名判断。 */
public enum ScheduleState implements WireEnum {
    /** 等待到点。 */
    SCHEDULED,
    /** 已领取运行编号，还没确认启动。 */
    DISPATCHING, RUNNING,
    /** 等外部条件，到点再唤醒。 */
    WAITING, PAUSED, COMPLETED, CANCELLED,
    /** 需要人工处理，不再自动调度。 */
    NEEDS_ATTENTION;

    /** 已结束，不会再被调度。 */
    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == NEEDS_ATTENTION;
    }

    /** 同上，直接判断落库字符串；未知值不算结束。 */
    public static boolean terminal(String state) {
        return WireEnum.parse(ScheduleState.class, state).map(ScheduleState::terminal).orElse(false);
    }

    /** 已经派发给运行器：正在领取或正在执行。 */
    public static boolean dispatched(String state) {
        return WireEnum.in(state, RUNNING, DISPATCHING);
    }
}
