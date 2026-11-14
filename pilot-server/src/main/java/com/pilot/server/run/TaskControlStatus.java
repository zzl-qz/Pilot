package com.pilot.server.run;

import com.pilot.core.util.WireEnum;

/** 任务合同的状态。名字就是任务文件和接口里的字符串，前端按同名判断。 */
public enum TaskControlStatus implements WireEnum {
    ACTIVE, PAUSED, INTERRUPTED, AWAITING_REVIEW, COMPLETED, FAILED, BLOCKED, BUDGET_LIMITED, WAITING, NEED_INPUT,
    UNVERIFIED, NO_PROGRESS, REVIEW_UNAVAILABLE, VERIFICATION_FAILED
}
