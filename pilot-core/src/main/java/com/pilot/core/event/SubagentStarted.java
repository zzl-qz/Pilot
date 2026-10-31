package com.pilot.core.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 子代理开始工作。子代理有独立上下文，只把最终结论回传给父代理。
 *
 * @param agentId 子代理标识，同一父 run 内唯一
 * @param task    派给它的任务描述
 * @param name    给人看的简短名字（如「统计 Java 文件」）；没起名时为 null，序列化时省略，界面会从任务里推一个
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubagentStarted(String agentId, String task, String name) implements SessionEvent {
    public SubagentStarted {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        task = task == null ? "" : task;
        name = name == null || name.isBlank() ? null : name.strip();
    }

    public SubagentStarted(String agentId, String task) {
        this(agentId, task, null);
    }

    @Override
    public EventType type() {
        return EventType.SUBAGENT_STARTED;
    }
}
