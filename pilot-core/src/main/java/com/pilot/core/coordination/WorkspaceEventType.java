package com.pilot.core.coordination;

/** 工作区协调事件的线上类型；命名与会话事件一致，使用点分隔风格。 */
public enum WorkspaceEventType {
    SESSION_STARTED("session.started"), SESSION_STOPPED("session.stopped"), RUN_STARTED("run.started"),
    RUN_FINISHED("run.finished"), SANDBOX_CHANGED("sandbox.changed"), FILE_CLAIMED("file.claimed"),
    FILE_RELEASED("file.released"), ARBITRATION_CREATED("arbitration.created"),
    ARBITRATION_RESOLVED("arbitration.resolved"), COORDINATION_REQUESTED("coordination.requested"),
    COORDINATION_UPDATED("coordination.updated"), COORDINATION_ALERT("coordination.alert"),
    HANDOFF_CREATED("handoff.created"), HANDOFF_RESOLVED("handoff.resolved");

    private final String wireName;

    WorkspaceEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
