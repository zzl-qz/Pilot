package com.pilot.core.event;

/**
 * 子 Agent 往沙箱提交了一份改动。界面据此标记「哪个 Agent 改了哪个文件」。
 *
 * @param sandboxId 沙箱标识
 * @param agentId   产出改动的子 Agent
 * @param taskId    归属任务
 * @param path      工作区相对路径
 * @param added     新增行数
 * @param removed   删除行数
 */
public record SandboxDiffReady(String sandboxId, String agentId, String taskId, String path,
                               int added, int removed) implements SessionEvent {
    public SandboxDiffReady {
        sandboxId = sandboxId == null ? "" : sandboxId;
        agentId = agentId == null ? "" : agentId;
        taskId = taskId == null ? "" : taskId;
        path = path == null ? "" : path;
        added = Math.max(0, added);
        removed = Math.max(0, removed);
    }

    @Override
    public EventType type() {
        return EventType.SANDBOX_DIFF;
    }
}
