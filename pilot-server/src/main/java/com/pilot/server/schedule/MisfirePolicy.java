package com.pilot.server.schedule;

import com.pilot.core.util.WireEnum;

/** 周期计划错过执行时间后的处理方式。名字就是接口里的字符串。 */
public enum MisfirePolicy implements WireEnum {
    /** 补跑一次。 */
    ONCE,
    /** 超过宽限就跳过这一轮。 */
    SKIP
}
