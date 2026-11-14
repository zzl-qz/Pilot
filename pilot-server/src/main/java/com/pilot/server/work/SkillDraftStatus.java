package com.pilot.server.work;

import com.pilot.core.util.WireEnum;

/** 技能草稿的状态。名字就是草稿记录和接口里的字符串。 */
public enum SkillDraftStatus implements WireEnum {
    PENDING,
    /** 已记下「接受」意图，正在写技能文件；重启后按文件指纹收敛。 */
    APPROVING, APPROVED,
    /** 已记下「撤回」意图，正在恢复原文件。 */
    REVERTING, REVERTED, REJECTED,
    /** 技能文件被别处改过，草稿不能再自动应用。 */
    CONFLICT
}
