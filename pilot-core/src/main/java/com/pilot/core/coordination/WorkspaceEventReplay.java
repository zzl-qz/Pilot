package com.pilot.core.coordination;

import java.util.List;

/** 历史事件回放：按游标取范围内可见的事件，用于断线重连。 */
public interface WorkspaceEventReplay {

    /** 序号大于 {@code afterSequence} 且对该范围可见的事件，按序最多 {@code limit} 条。 */
    List<WorkspaceCoordinationEvent> replay(long afterSequence, ProjectCoordinationScope scope, int limit);

    /** 已经发布过的最大序号；没有事件时为 0。 */
    long latestSequence();

    /**
     * 仍然保留在缓存里的最小序号；缓存为空时等于 {@link #latestSequence()}。
     *
     * <p>客户端游标小于它说明历史已被挤掉，需要先重新拉取快照再续传。
     */
    long earliestRetainedSequence();
}
