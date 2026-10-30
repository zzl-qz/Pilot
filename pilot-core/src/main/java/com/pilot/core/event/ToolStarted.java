package com.pilot.core.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具开始执行。callId 用于关联同一工具调用的后续事件。
 *
 * @param agentId 发起这次调用的子智能体；主智能体自己的调用为 null（序列化时省略该字段，
 *                因此历史事件与旧客户端都不受影响）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolStarted(String callId, String name, String summary, String agentId) implements SessionEvent {

    public ToolStarted(String callId, String name, String summary) {
        this(callId, name, summary, null);
    }

    /** 同一调用，改记到指定子智能体名下。 */
    public ToolStarted forAgent(String agent) {
        return new ToolStarted(callId, name, summary, agent == null || agent.isBlank() ? null : agent);
    }

    @Override
    public EventType type() {
        return EventType.TOOL_STARTED;
    }
}
