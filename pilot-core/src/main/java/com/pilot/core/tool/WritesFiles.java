package com.pilot.core.tool;

/**
 * 会改动工作区文件的工具实现该接口，使 {@link ToolExecutor} 能把变更路径汇总给 AgentLoop，
 * 最终写进 {@code run.completed.changedFiles}。返回值是展示用的相对路径，未改动时返回 {@code null}。
 */
public interface WritesFiles {
    String changedPath(ToolCall call);
}
