package com.pilot.core.event;

/**
 * 子代理结束。
 *
 * @param agentId    子代理标识
 * @param summary    回传给父代理的结论（成功或阶段交付时非空）
 * @param ok         是否正常完成
 * @param stopReason 结束原因码（如 {@code COMPLETED}、{@code MAX_STEPS}、{@code MODEL_ERROR}）；旧数据为空字符串
 * @param partial    未完整走完但已带回可用阶段结论时为 {@code true}
 */
public record SubagentFinished(String agentId, String summary, boolean ok, String stopReason,
    boolean partial) implements SessionEvent {
    public SubagentFinished(String agentId, String summary, boolean ok) {
        this(agentId, summary, ok, ok ? "COMPLETED" : "", false);
    }

    public SubagentFinished {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        summary = summary == null ? "" : summary;
        stopReason = stopReason == null ? "" : stopReason.strip();
    }

    @Override
    public EventType type() {
        return EventType.SUBAGENT_FINISHED;
    }
}
