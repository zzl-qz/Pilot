package com.pilot.core.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 内存版会话服务：保存会话、运行与事件序列，并向订阅者推送事件。
 *
 * <p>存储与推送都在会话级锁内完成，因此订阅者收到的事件顺序与 {@code sequence} 一致；
 * 订阅时先回放游标之后的历史事件，再注册监听，保证不丢事件、不重复。
 * 进程重启后数据丢失，这是 Sprint 1 的预期限制。
 */
public final class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final Clock clock;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionService() {
        this(Clock.systemUTC());
    }

    public SessionService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Session createSession(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        var state = new SessionState("ses_" + UUID.randomUUID(), title.strip(), clock.instant());
        sessions.put(state.id, state);
        return state.snapshot();
    }

    public Optional<Session> find(String sessionId) {
        SessionState state = sessionId == null ? null : sessions.get(sessionId);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    /** 记录一次用户消息并开启新运行：立即写入 {@code run.started} 事件。 */
    public Run sendMessage(String sessionId, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        SessionState state = require(sessionId);
        String runId = "run_" + UUID.randomUUID();
        Instant startedAt = clock.instant();
        publish(state, runId, new RunStarted(content.strip()));
        return new Run(runId, sessionId, startedAt);
    }

    /** 追加事件并推送给当前订阅者，返回带序号与时间戳的信封。 */
    public EventEnvelope publish(String sessionId, String runId, SessionEvent event) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(event, "event must not be null");
        return publish(require(sessionId), runId, event);
    }

    /** 返回会话的全部事件快照，按序号升序。 */
    public List<EventEnvelope> events(String sessionId) {
        SessionState state = require(sessionId);
        synchronized (state) {
            return List.copyOf(state.events);
        }
    }

    /** 返回序号大于 {@code afterSequence} 的事件，用于断线重连。 */
    public List<EventEnvelope> eventsAfter(String sessionId, long afterSequence) {
        SessionState state = require(sessionId);
        synchronized (state) {
            var result = new ArrayList<EventEnvelope>();
            for (EventEnvelope envelope : state.events) {
                if (envelope.sequence() > afterSequence) {
                    result.add(envelope);
                }
            }
            return List.copyOf(result);
        }
    }

    /**
     * 订阅会话事件：先按顺序回放 {@code afterSequence} 之后的历史事件，再接收后续新事件。
     *
     * @throws NoSuchSessionException 会话不存在
     */
    public EventSubscription subscribe(String sessionId, long afterSequence, Consumer<EventEnvelope> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        SessionState state = require(sessionId);
        synchronized (state) {
            for (EventEnvelope envelope : state.events) {
                if (envelope.sequence() > afterSequence) {
                    listener.accept(envelope);
                }
            }
            state.subscribers.add(listener);
        }
        return () -> state.subscribers.remove(listener);
    }

    private EventEnvelope publish(SessionState state, String runId, SessionEvent event) {
        synchronized (state) {
            var envelope = EventEnvelope.of(++state.sequence, clock.instant(), state.id, runId, event);
            state.events.add(envelope);
            state.status = nextStatus(state.status, event);
            for (Consumer<EventEnvelope> subscriber : state.subscribers) {
                try {
                    subscriber.accept(envelope);
                } catch (RuntimeException e) {
                    state.subscribers.remove(subscriber);
                    log.warn("Dropped session subscriber for {} after delivery failure", state.id, e);
                }
            }
            return envelope;
        }
    }

    private SessionState require(String sessionId) {
        SessionState state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) {
            throw new NoSuchSessionException(sessionId);
        }
        return state;
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

    private static final class SessionState {
        private final String id;
        private final String title;
        private final Instant createdAt;
        private final List<EventEnvelope> events = new ArrayList<>();
        private final CopyOnWriteArrayList<Consumer<EventEnvelope>> subscribers = new CopyOnWriteArrayList<>();
        private SessionStatus status = SessionStatus.CREATED;
        private long sequence;

        private SessionState(String id, String title, Instant createdAt) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
        }

        private Session snapshot() {
            synchronized (this) {
                return new Session(id, title, createdAt, status);
            }
        }
    }
}
