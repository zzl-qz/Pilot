package com.pilot.core.tool.command;

import com.pilot.core.util.WireEnum;

/** 命令任务的状态。名字就是任务文件、工具输出和接口里的字符串。 */
public enum CommandTaskStatus implements WireEnum {
    RUNNING,
    /** 已请求停止，进程树还没退干净。 */
    STOPPING, COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED,
    /** 输出超过上限被终止。 */
    OUTPUT_LIMIT;

    /** 进程还在：正在运行或正在停止。 */
    public static boolean active(String status) {
        return WireEnum.in(status, RUNNING, STOPPING);
    }
}
