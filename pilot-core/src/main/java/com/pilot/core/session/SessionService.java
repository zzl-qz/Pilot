package com.pilot.core.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunFailed;
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

    /**
     * 把正在生成的文字推给当前界面，不写入会话历史。
     * 序号沿用最后一条已落库事件，避免浏览器把预览当成新游标，重连时漏掉后面的正式事件。
     */
    public void preview(String sessionId, String runId, SessionEvent event) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                return;
            }
            long sequence = repository.latestSequence(sessionId);
            if (sequence < 1) {
                return;
            }
            EventEnvelope envelope = EventEnvelope.of(sequence, clock.instant(), sessionId, runId, event);
            for (Consumer<EventEnvelope> subscriber : listeners(sessionId)) {
                try {
                    subscriber.accept(envelope);
                } catch (RuntimeException e) {
                    listeners(sessionId).remove(subscriber);
                    log.debug("Dropped session subscriber for {}: {}", sessionId, e.toString());
                }
            }
        }
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
                    // 订阅者通常是已断开的 SSE 连接，属于正常情况：摘掉即可，不必打印堆栈
                    listeners(sessionId).remove(subscriber);
                    log.debug("Dropped session subscriber for {}: {}", sessionId, e.toString());
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
     * 从某条用户消息起重写对话时，把这条以及后面的事件删掉。
     * 和 {@link #publish} 共用会话锁，避免删到一半又被追加。
     */
    public int dropEventsFrom(String sessionId, long fromSequence) {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                throw new NoSuchSessionException(sessionId);
            }
            return repository.dropEventsFrom(sessionId, fromSequence);
        }
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
            // 分歧 / 冲突 / 校验失败与工具审批一样，都是「等人做决定」
            case APPROVAL_REQUIRED, ARBITRATION_REQUIRED -> SessionStatus.WAITING_APPROVAL;
            case APPROVAL_RESOLVED, ARBITRATION_RESOLVED -> SessionStatus.RUNNING;
            case RUN_COMPLETED -> SessionStatus.COMPLETED;
            // 进程重启导致的中断和真正的失败要分开：前者可继续，不该显示成「失败」
            case RUN_FAILED -> isInterrupted(event) ? SessionStatus.INTERRUPTED : SessionStatus.FAILED;
            case RUN_CANCELLED -> SessionStatus.CANCELLED;
            default -> current;
        };
    }

    /** {@code run.failed} 的 code 为 INTERRUPTED 表示运行不是自己结束的（服务重启）。 */
    private static boolean isInterrupted(SessionEvent event) {
        return event instanceof RunFailed failed && "INTERRUPTED".equals(failed.code());
    }
}
