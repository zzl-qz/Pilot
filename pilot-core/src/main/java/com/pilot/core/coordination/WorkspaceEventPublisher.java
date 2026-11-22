package com.pilot.core.coordination;

/** 发布工作区协调事件的唯一入口；实现方负责分配序号并做有界缓存。 */
public interface WorkspaceEventPublisher {

    /**
     * 发布一条事件，返回带上序号与时间的结果。
     *
     * @throws IllegalArgumentException 事件缺少类型或会话标识
     */
    WorkspaceCoordinationEvent publish(WorkspaceEventDraft draft);
}
