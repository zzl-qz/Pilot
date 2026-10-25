package com.pilot.core.session;

/** 事件订阅句柄。关闭后不再收到新事件。 */
@FunctionalInterface
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}
