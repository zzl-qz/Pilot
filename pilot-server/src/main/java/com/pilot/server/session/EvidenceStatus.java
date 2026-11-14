package com.pilot.server.session;

import com.pilot.core.util.WireEnum;

/**
 * 证据账本里一条证据的状态。
 * 大多数直接取工具结果状态 ToolExecutor.OutcomeStatus 的名字，这里只列业务判断要点名的几个，
 * 外加账本自己的 STALE。名字就是任务文件里 evidence.status 的字符串。
 */
public enum EvidenceStatus implements WireEnum {
    SUCCESS, RUNNING, PERMANENT_FAILURE, SKIPPED,
    /** 记录时用户要求已经变了，这条证据不能证明当前版本。 */
    STALE,
    /** 后台命令正在停止；只用来排除还没结束的命令任务。 */
    STOPPING
}
