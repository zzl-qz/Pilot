package com.pilot.core.event;

/**
 * 子任务状态变化。界面按任务卡片渲染，父 Agent 用它维护 DAG 推进。
 *
 * @param taskId  任务编号
 * @param agentId 当前执行的子 Agent；未分配时为空字符串
 * @param title   任务标题
 * @param status  {@link com.pilot.core.multi.TaskStatus#wireName()}
 * @param detail  一句话说明（失败原因、仲裁摘要等）
 */
public record TaskUpdated(String taskId, String agentId, String title, String status, String detail)
        implements SessionEvent {
    public TaskUpdated {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        agentId = agentId == null ? "" : agentId;
        title = title == null ? "" : title;
        status = status == null ? "" : status;
        detail = detail == null ? "" : detail;
    }

    @Override
    public EventType type() {
        return EventType.TASK_UPDATED;
    }
}
