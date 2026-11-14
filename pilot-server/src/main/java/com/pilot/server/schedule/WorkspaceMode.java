package com.pilot.server.schedule;

import com.pilot.core.util.WireEnum;

/** 计划在哪里执行。名字就是落库和接口里的字符串。 */
public enum WorkspaceMode implements WireEnum {
    /** 独立的 Git 工作区，结束后由用户交回。 */
    ISOLATED,
    /** 直接在来源对话的目录里执行。 */
    SHARED
}
