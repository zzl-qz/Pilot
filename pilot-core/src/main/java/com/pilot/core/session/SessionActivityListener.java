package com.pilot.core.session;

/**
 * 会话开停通知端口：会话服务只负责在生命周期节点上通知，怎么记录或推送由上层决定。
 *
 * <p>实现方不能反过来修改会话状态，也不能阻塞会话创建与归档。
 */
public interface SessionActivityListener {

    /** 不关心会话开停时使用的空实现。 */
    SessionActivityListener NONE = new SessionActivityListener() {
        @Override
        public void started(Session session) {
        }

        @Override
        public void stopped(Session session) {
        }
    };

    /** 会话刚被创建，或从归档恢复。 */
    void started(Session session);

    /** 会话被归档，不再参与实时协作。 */
    void stopped(Session session);
}
