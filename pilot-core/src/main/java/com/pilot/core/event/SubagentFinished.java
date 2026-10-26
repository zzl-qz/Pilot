package com.pilot.core.event;

/**
 * 子代理结束。
 *
 * @param agentId 子代理标识
 * @param summary 回传给父代理的结论（成功时非空）
 * @param ok      是否正常完成
 */
public record SubagentFinished(String agentId, String summary, boolean ok) implements SessionEvent {
    public SubagentFinished {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        summary = summary == null ? "" : summary;
    }

    @Override
    public EventType type() {
        return EventType.SUBAGENT_FINISHED;
    }
}
