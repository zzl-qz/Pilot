package com.pilot.server.schedule;

import com.pilot.core.util.WireEnum;

/** 周期计划的状态。名字就是落库和接口里的字符串。 */
public enum SeriesState implements WireEnum {
    ACTIVE, PAUSED, CANCELLED,
    /** 无法再产生轮次，需要用户重建。 */
    ERROR
}
