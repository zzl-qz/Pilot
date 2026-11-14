package com.pilot.core.event;

import com.pilot.core.util.WireEnum;

/**
 * 运行没有正常结束时的原因码，出现在 RunFailed.code、循环结果和策略的 Stop 里。
 * 名字就是事件和接口里的字符串，客户端按它区分处理；新增原因码先加到这里。
 */
public enum RunFailureCode implements WireEnum {
    // 循环自己判定的
    CANCELLED, BUDGET_EXHAUSTED, CONTEXT_TOO_LARGE, MODEL_ERROR, MODEL_INCOMPLETE, MODEL_REPEATED, OUTPUT_LIMIT,
    MAX_STEPS, TASK_INCOMPLETE, NO_PROGRESS, WAITING,

    // 任务策略要求停下，名字与任务状态相同
    NEED_INPUT, BLOCKED, BUDGET_LIMITED, REVIEW_UNAVAILABLE, VERIFICATION_FAILED,

    // 服务端运行管理
    PAUSED, INTERRUPTED, INTERNAL_ERROR, START_FAILED, QUEUE_START_FAILED
}
