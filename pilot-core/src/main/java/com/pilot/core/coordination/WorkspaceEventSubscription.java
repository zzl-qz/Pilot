package com.pilot.core.coordination;

/** 一个工作区事件订阅；关闭后不再投递，并记录关闭原因。 */
public interface WorkspaceEventSubscription {

    boolean isClosed();

    /** 关闭原因，例如 {@code closed}、{@code queue_overflow}、{@code delivery_error}。 */
    String closeReason();

    /** 已经投递给订阅者的最后一个序号。 */
    long cursor();

    /** 已投递的事件条数。 */
    long deliveredCount();

    void close();
}
