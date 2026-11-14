package com.pilot.server.session;

import com.pilot.core.util.WireEnum;

/** 任务执行所处的阶段。名字就是任务文件里 execution.phase 的字符串，前端按同名展示。 */
public enum ExecutionPhase implements WireEnum {
    EXECUTING, VERIFYING, VERIFIED, UNVERIFIED,

    // 普通任务收尾时对实际检查的汇总
    CHECKS_PASSED, CHECKS_FAILED, CHECKS_STALE,

    VERIFICATION_FAILED,

    // 目标任务核验后停下的原因，名字与 ReviewDecision 相同
    WAIT, NEED_INPUT, BLOCKED, IMPOSSIBLE,

    /** 旧版本写入的值，升级策略时会重新开始。 */
    NO_PROGRESS;

    /** 普通任务已经收尾：有检查结论，或明确记为未核验。 */
    public static boolean directFinished(String phase) {
        return WireEnum.in(phase, CHECKS_PASSED, CHECKS_FAILED, CHECKS_STALE, UNVERIFIED);
    }
}
