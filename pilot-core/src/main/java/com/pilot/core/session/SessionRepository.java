package com.pilot.core.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.SessionEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储端口。只负责数据读写，不处理订阅推送（那属于 {@link SessionService}）。
 *
 * <p>{@link #append} 必须原子地分配会话内递增的 {@code sequence} 并落库，
 * 保证并发发布时事件序号不重复、可按序回放。
 */
public interface SessionRepository {

    Session create(String sessionId, String title, String workspace, Instant createdAt);

    Optional<Session> find(String sessionId);

    /** 按创建时间倒序返回全部会话。 */
    List<Session> list();

    boolean rename(String sessionId, String title);

    /** 删除会话及其事件；返回是否真的删除了记录。 */
    boolean delete(String sessionId);

    List<EventEnvelope> events(String sessionId);

    List<EventEnvelope> eventsAfter(String sessionId, long afterSequence);

    /**
     * 删掉序号大于等于 {@code fromSequence} 的事件。
     * 会话自己的序号计数器不动，后面新事件继续递增，避免和已经发出去的 SSE 游标撞号。
     *
     * @return 实际删掉的条数
     */
    int dropEventsFrom(String sessionId, long fromSequence);

    /**
     * 追加事件。
     *
     * @param status 追加后的会话状态，由调用方根据事件类型计算
     * @throws NoSuchSessionException 会话不存在
     */
    EventEnvelope append(String sessionId, String runId, Instant timestamp, SessionEvent event, SessionStatus status);

    /** 清理实现自带的资源（例如数据库连接池），默认无事可做。 */
    default void close() {
    }
}
