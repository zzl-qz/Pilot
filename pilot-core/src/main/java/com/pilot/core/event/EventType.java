package com.pilot.core.event;

/**
 * v1 事件类型。线上名称使用点分隔风格（与 Codex App Server 的事件命名习惯一致）。
 */
public enum EventType {
    RUN_STARTED("run.started"),
    ASSISTANT_DELTA("assistant.delta"),
    CONTEXT_COMPACTED("context.compacted"),
    PLAN_UPDATED("plan.updated"),
    RUN_STEERED("run.steered"),
    RUN_CONTINUED("run.continued"),
    SUBAGENT_STARTED("subagent.started"),
    SUBAGENT_FINISHED("subagent.finished"),
    TOOL_STARTED("tool.started"),
    APPROVAL_REQUIRED("approval.required"),
    APPROVAL_RESOLVED("approval.resolved"),
    TOOL_COMPLETED("tool.completed"),
    RUN_FAILED("run.failed"),
    RUN_CANCELLED("run.cancelled"),
    RUN_COMPLETED("run.completed");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
