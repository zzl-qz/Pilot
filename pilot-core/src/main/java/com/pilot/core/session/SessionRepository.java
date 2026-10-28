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

    Session create(String sessionId, String title, String workspace, Instant createdAt, String projectId,
                   boolean isBlank);

    /** 已经发过消息：清掉空白标记（幂等）。返回是否真的改动过。 */
    boolean markNotBlank(String sessionId);

    /** 归档 / 取消归档。 */
    boolean setArchived(String sessionId, boolean archived);

    /** 兼容旧调用：不归属任何项目、不是空白对话。 */
    default Session create(String sessionId, String title, String workspace, Instant createdAt) {
        return create(sessionId, title, workspace, createdAt, null, false);
    }

    /** 兼容旧调用：不归属项目的普通对话。 */
    default Session create(String sessionId, String title, String workspace, Instant createdAt, String projectId) {
        return create(sessionId, title, workspace, createdAt, projectId, false);
    }

    Optional<Session> find(String sessionId);

    /** 按创建时间倒序返回全部会话。 */
    List<Session> list();

    /** 某个项目下的会话，按创建时间倒序。 */
    default List<Session> listByProject(String projectId) {
        return list().stream()
                .filter(session -> java.util.Objects.equals(session.projectId(), projectId))
                .toList();
    }

    /** 项目下唯一的那条「未归档空白对话」，没有则返回空。 */
    default Optional<Session> findBlank(String projectId) {
        return listByProject(projectId).stream()
                .filter(session -> session.isBlank() && !session.archived())
                .findFirst();
    }

    /** 切换会话当前使用的文件夹（同一项目绑定的其它目录）。 */
    boolean updateWorkspace(String sessionId, String workspace);

    /** 回退历史后同步会话状态，不改变事件序号。 */
    boolean updateStatus(String sessionId, SessionStatus status);

    boolean rename(String sessionId, String title);

    /** 删除会话及其事件；返回是否真的删除了记录。 */
    boolean delete(String sessionId);

    List<EventEnvelope> events(String sessionId);

    List<EventEnvelope> eventsAfter(String sessionId, long afterSequence);

    /** 会话的事件总数。默认实现不读事件体，仅计数。 */
    default long eventCount(String sessionId) {
        return events(sessionId).size();
    }

    /**
     * 历史分页：返回序号小于 {@code before} 的最后 {@code limit} 条事件（{@code before} 为空表示从最新往前）。
     * 默认实现基于全量读取再切片，落库实现应覆盖成带 LIMIT 的查询。
     */
    default List<EventEnvelope> eventsPage(String sessionId, Long before, int limit) {
        List<EventEnvelope> all = events(sessionId);
        int end = all.size();
        if (before != null) {
            end = 0;
            while (end < all.size() && all.get(end).sequence() < before) {
                end++;
            }
        }
        int start = Math.max(0, end - Math.max(1, limit));
        return List.copyOf(all.subList(start, end));
    }

    /** 当前已落库的最大序号。还没有事件时返回 0。 */
    long latestSequence(String sessionId);

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
