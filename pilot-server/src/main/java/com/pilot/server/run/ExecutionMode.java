package com.pilot.server.run;

import com.pilot.core.util.WireEnum;

/** 任务的执行方式。名字就是任务文件里 execution.mode 的字符串。 */
public enum ExecutionMode implements WireEnum {
    /** 普通任务：直接执行，收尾只汇总实际检查，不做独立核验。 */
    DIRECT,
    /** 目标任务：跨轮追踪，收尾由核验器独立判断是否完成。 */
    GOAL,
    /** 旧版本写入的值，读到后按当前策略重新归类。 */
    UNASSESSED, PROBE
}
