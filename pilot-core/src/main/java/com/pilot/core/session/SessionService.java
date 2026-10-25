package com.pilot.core.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 会话服务：负责 id 生成、状态推进与事件订阅，数据读写委托给 {@link SessionRepository}。
 *
 * <p>同一会话的「追加事件 + 推送订阅者」在同一把会话锁内完成，因此订阅者收到的事件顺序与
 * {@code sequence} 一致；订阅时先回放游标之后的历史事件，再注册监听，保证不丢不重。
 */
public final class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final Clock clock;
    private final SessionRepository repository;
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<EventEnvelope>>> subscribers =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public SessionService() {
        this(Clock.systemUTC(), new InMemorySessionRepository());
    }

    public SessionService(Clock clock) {
        this(clock, new InMemorySessionRepository());
    }

    public SessionService(Clock clock, SessionRepository repository) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public Session createSession(String title) {
        return createSession(title, null);
    }

    /**
     * @param workspace 会话绑定的工作区绝对路径；{@code null} 表示沿用服务端默认工作区
     */
    public Session createSession(String title, String workspace) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String normalizedWorkspace = workspace == null || workspace.isBlank() ? null : workspace.strip();
        return repository.create("ses_" + UUID.randomUUID(), title.strip(), normalizedWorkspace, clock.instant());
    }

    public Optional<Session> find(String sessionId) {
        return sessionId == null ? Optional.empty() : repository.find(sessionId);
    }

    /** 按创建时间倒序返回全部会话。 */
    public List<Session> list() {
        return repository.list();
    }

    public boolean rename(String sessionId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return repository.rename(sessionId, title.strip());
    }

    /** 删除会话：先摘掉订阅者再删数据，避免删除后仍有推送。 */
    public boolean delete(String sessionId) {
        subscribers.remove(sessionId);
        locks.remove(sessionId);
        return repository.delete(sessionId);
    }

    /** 记录一次用户消息并开启新运行：立即写入 {@code run.started} 事件。 */
    public Run sendMessage(String sessionId, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        String runId = "run_" + UUID.randomUUID();
        Instant startedAt = clock.instant();
        publish(sessionId, runId, new RunStarted(content.strip()));
        return new Run(runId, sessionId, startedAt);
    }

    /** 追加事件并推送给当前订阅者，返回带序号与时间戳的信封。 */
    public EventEnvelope publish(String sessionId, String runId, SessionEvent event) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            Session current = repository.find(sessionId).orElseThrow(() -> new NoSuchSessionException(sessionId));
            SessionStatus status = nextStatus(current.status(), event);
            EventEnvelope envelope = repository.append(sessionId, runId, clock.instant(), event, status);
            for (Consumer<EventEnvelope> subscriber : listeners(sessionId)) {
                try {
                    subscriber.accept(envelope);
                } catch (RuntimeException e) {
                    listeners(sessionId).remove(subscriber);
                    log.warn("Dropped session subscriber for {} after delivery failure", sessionId, e);
                }
            }
            return envelope;
        }
    }

    /** 返回会话的全部事件快照，按序号升序。 */
    public List<EventEnvelope> events(String sessionId) {
        return repository.events(sessionId);
    }

    /** 返回序号大于 {@code afterSequence} 的事件，用于断线重连。 */
    public List<EventEnvelope> eventsAfter(String sessionId, long afterSequence) {
        return repository.eventsAfter(sessionId, afterSequence);
    }

    /**
     * 订阅会话事件：先按顺序回放 {@code afterSequence} 之后的历史事件，再接收后续新事件。
     *
     * @throws NoSuchSessionException 会话不存在
     */
    public EventSubscription subscribe(String sessionId, long afterSequence, Consumer<EventEnvelope> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                throw new NoSuchSessionException(sessionId);
            }
            for (EventEnvelope envelope : repository.eventsAfter(sessionId, afterSequence)) {
                listener.accept(envelope);
            }
            listeners(sessionId).add(listener);
        }
        return () -> listeners(sessionId).remove(listener);
    }

    private Object lockFor(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return locks.computeIfAbsent(sessionId, key -> new Object());
    }

    private CopyOnWriteArrayList<Consumer<EventEnvelope>> listeners(String sessionId) {
        return subscribers.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());
    }

    private static SessionStatus nextStatus(SessionStatus current, SessionEvent event) {
        return switch (event.type()) {
            case RUN_STARTED -> SessionStatus.RUNNING;
            case APPROVAL_REQUIRED -> SessionStatus.WAITING_APPROVAL;
            case APPROVAL_RESOLVED -> SessionStatus.RUNNING;
            case RUN_COMPLETED -> SessionStatus.COMPLETED;
            case RUN_FAILED -> SessionStatus.FAILED;
            default -> current;
        };
    }
}
