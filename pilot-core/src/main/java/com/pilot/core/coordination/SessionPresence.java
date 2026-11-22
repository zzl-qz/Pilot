package com.pilot.core.coordination;

/**
 * 会话在线状态的只读端口。
 *
 * <p>在线状态属于运行时：由当前进程的订阅连接登记，重启后全部消失，不会被持久化成「还在线」。
 */
public interface SessionPresence {

    /** 没有登记任何连接时使用：一律离线。 */
    SessionPresence NONE = sessionId -> false;

    boolean isOnline(String sessionId);
}
