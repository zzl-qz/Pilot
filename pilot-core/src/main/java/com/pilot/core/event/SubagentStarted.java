package com.pilot.core.event;

/**
 * 子代理开始工作。子代理有独立上下文，只把最终结论回传给父代理。
 *
 * @param agentId 子代理标识，同一父 run 内唯一
 * @param task    派给它的任务描述
 */
public record SubagentStarted(String agentId, String task) implements SessionEvent {
    public SubagentStarted {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        task = task == null ? "" : task;
    }

    @Override
    public EventType type() {
        return EventType.SUBAGENT_STARTED;
    }
}
