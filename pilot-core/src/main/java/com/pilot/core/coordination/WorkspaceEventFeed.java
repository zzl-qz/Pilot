package com.pilot.core.coordination;

import java.util.function.Consumer;

/** 实时事件订阅：先补历史，再接实时，投递顺序与发布顺序一致且不重复。 */
public interface WorkspaceEventFeed {

    /**
     * 订阅某个协调范围内的事件。
     *
     * @param scope         订阅者的协调范围，决定能看到哪些事件
     * @param afterSequence 已经处理到的序号，订阅时先补发它之后的事件
     * @param listener      投递回调，在订阅自己的线程上执行
     * @throws IllegalStateException 订阅数量已达上限
     */
    WorkspaceEventSubscription subscribe(ProjectCoordinationScope scope, long afterSequence,
        Consumer<WorkspaceCoordinationEvent> listener);

    /** 当前订阅数量。 */
    int subscriptionCount();
}
