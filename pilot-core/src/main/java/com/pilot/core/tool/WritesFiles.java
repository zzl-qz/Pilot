package com.pilot.core.tool;

import com.pilot.core.message.ToolCall;

/**
 * 会改动工作区文件的工具实现该接口。
 * 执行器据此把变更路径汇总给 AgentLoop，最终写进 run.completed.changedFiles。
 * 返回值是展示用的相对路径，没改动时返回 null。
 */
public interface WritesFiles {
    String changedPath(ToolCall call);
}
