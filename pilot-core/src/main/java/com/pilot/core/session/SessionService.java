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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 会话服务：负责 id 生成、状态推进与事件订阅，数据读写委托给 {@link SessionRepository}。
 *
 * <p>同一会话的「追加事件 + 推送订阅者」在同一把会话锁内完成，因此订阅者收到的事件顺序与
 * {@code sequence} 一致；订阅时先回放游标之后的历史事件，再注册监听，保证不丢不重。
 *
 * <p>推送不在锁内直接写连接：每个订阅者有一个有界队列和独立投递线程。慢客户端只会把自己
 * 的队列填满并被断开（断线后按游标重连即可补齐），不会拖住会话的发布线程和 Agent 循环。
 */
public final class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    /** 单个订阅者允许积压的事件数。超过即视为客户端跟不上，直接断开。 */
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 4096;

    private final Clock clock;
    private final SessionRepository repository;
    private final ConcurrentMap<String, CopyOnWriteArrayList<EventDelivery>> subscribers =
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
        return createSession(title, workspace, null);
    }

    /**
     * @param projectId 所属项目；{@code null} 表示不归属项目（历史调用方式）
     * @param isBlank   还没有发过消息的空白对话；同一项目下最多一条
     */
    public Session createSession(String title, String workspace, String projectId, boolean isBlank) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String normalizedWorkspace = workspace == null || workspace.isBlank() ? null : workspace.strip();
        String normalizedProject = projectId == null || projectId.isBlank() ? null : projectId.strip();
        return repository.create("ses_" + UUID.randomUUID(), title.strip(), normalizedWorkspace,
                clock.instant(), normalizedProject, isBlank);
    }

    /** 兼容调用：按普通对话创建（非空白）。 */
    public Session createSession(String title, String workspace, String projectId) {
        return createSession(title, workspace, projectId, false);
    }

    /** 项目下唯一的那条未归档空白对话。 */
    public Optional<Session> findBlank(String projectId) {
        return repository.findBlank(projectId);
    }

    /** 归档 / 取消归档。 */
    public boolean setArchived(String sessionId, boolean archived) {
        return repository.setArchived(sessionId, archived);
    }

    /** 切换会话当前使用的文件夹。 */
    public boolean moveToWorkspace(String sessionId, String workspace) {
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException("workspace must not be blank");
        }
        return repository.updateWorkspace(sessionId, workspace.strip());
    }

    public Optional<Session> find(String sessionId) {
        return sessionId == null ? Optional.empty() : repository.find(sessionId);
    }

    /** 按创建时间倒序返回全部会话。 */
    public List<Session> list() {
        return repository.list();
    }

    /** 某个项目下的会话，按创建时间倒序。 */
    public List<Session> listByProject(String projectId) {
        return repository.listByProject(projectId);
    }

    public boolean rename(String sessionId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return repository.rename(sessionId, title.strip());
    }

    /** 删除会话：先摘掉订阅者再删数据，避免删除后仍有推送。 */
    public boolean delete(String sessionId) {
        CopyOnWriteArrayList<EventDelivery> existing = subscribers.remove(sessionId);
        if (existing != null) {
            // 不关掉投递线程的话，它们会一直空转等事件
            existing.forEach(EventDelivery::close);
        }
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
                locks.remove(sessionId, lock);
                return;
            }
            long sequence = repository.latestSequence(sessionId);
            if (sequence < 1) {
                return;
            }
            EventEnvelope envelope = EventEnvelope.of(sequence, clock.instant(), sessionId, runId, event);
            deliver(sessionId, envelope);
        }
    }

    /** 追加事件并推送给当前订阅者，返回带序号与时间戳的信封。 */
    public EventEnvelope publish(String sessionId, String runId, SessionEvent event) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            Session current = repository.find(sessionId).orElseGet(() -> {
                // 往不存在的会话发布事件：顺手清掉刚建的锁，避免 map 只增不减
                locks.remove(sessionId, lock);
                throw new NoSuchSessionException(sessionId);
            });
            SessionStatus status = nextStatus(current.status(), event);
            // 发过第一条消息就不再是空白对话。放在 publish 里，发送、重新生成、改历史重跑都覆盖
            if (event.type() == com.pilot.core.event.EventType.RUN_STARTED) {
                repository.markNotBlank(sessionId);
            }
            EventEnvelope envelope = repository.append(sessionId, runId, clock.instant(), event, status);
            deliver(sessionId, envelope);
            return envelope;
        }
    }

    /** 投递不阻塞：队列满说明客户端已经跟不上，摘掉它即可，历史事件还能按游标补。 */
    private void deliver(String sessionId, EventEnvelope envelope) {
        CopyOnWriteArrayList<EventDelivery> current = subscribers.get(sessionId);
        if (current == null) {
            return;
        }
        for (EventDelivery delivery : current) {
            delivery.offer(envelope);
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

    /** 会话的事件总数，用于列表/详情展示，不必把事件全读进内存。 */
    public long eventCount(String sessionId) {
        return repository.eventCount(sessionId);
    }

    /**
     * 指定序号区间的事件，供历史分页使用。
     *
     * @param before 只返回序号小于它的事件；{@code null} 表示从最新一条往前取
     * @param limit  最多返回多少条
     */
    public List<EventEnvelope> eventsPage(String sessionId, Long before, int limit) {
        return repository.eventsPage(sessionId, before, limit);
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
            int removed = repository.dropEventsFrom(sessionId, fromSequence);
            SessionStatus status = SessionStatus.CREATED;
            for (EventEnvelope event : repository.events(sessionId)) {
                status = nextStatus(status, event.payload());
            }
            repository.updateStatus(sessionId, status);
            return removed;
        }
    }

    /**
     * 订阅会话事件：先按顺序回放 {@code afterSequence} 之后的历史事件，再接收后续新事件。
     *
     * @throws NoSuchSessionException 会话不存在
     */
    public EventSubscription subscribe(String sessionId, long afterSequence, Consumer<EventEnvelope> listener) {
        return subscribe(sessionId, afterSequence, listener, () -> { });
    }

    /**
     * @param onClosed 订阅被动结束（客户端断开、队列积压溢出）时的回调，用于关闭底层连接
     */
    public EventSubscription subscribe(String sessionId, long afterSequence, Consumer<EventEnvelope> listener,
                                       Runnable onClosed) {
        Objects.requireNonNull(listener, "listener must not be null");
        var self = new java.util.concurrent.atomic.AtomicReference<EventDelivery>();
        EventDelivery delivery = new EventDelivery(envelope -> listener.accept(envelope), () -> {
            EventDelivery current = self.get();
            if (current != null) {
                unsubscribe(sessionId, current);
            }
            if (onClosed != null) {
                onClosed.run();
            }
        });
        self.set(delivery);
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                locks.remove(sessionId, lock);
                delivery.close();
                throw new NoSuchSessionException(sessionId);
            }
            // 回放同步执行：连接刚建立时先把历史写完，客户端连上即可看到全量进度。
            // 之后的实时事件才走队列，慢客户端不会因此拖住会话发布线程。
            for (EventEnvelope envelope : repository.eventsAfter(sessionId, afterSequence)) {
                delivery.deliverNow(envelope);
            }
            listeners(sessionId).add(delivery);
        }
        return delivery;
    }

    private Object lockFor(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return locks.computeIfAbsent(sessionId, key -> new Object());
    }

    private CopyOnWriteArrayList<EventDelivery> listeners(String sessionId) {
        return subscribers.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());
    }

    private void unsubscribe(String sessionId, EventDelivery delivery) {
        subscribers.compute(sessionId, (key, current) -> {
            if (current == null) {
                return null;
            }
            current.remove(delivery);
            return current.isEmpty() ? null : current;
        });
    }

    private static SessionStatus nextStatus(SessionStatus current, SessionEvent event) {
        return switch (event.type()) {
            case RUN_STARTED -> SessionStatus.RUNNING;
            // 分歧 / 冲突 / 校验失败与工具审批一样，都是「等人做决定」
            case APPROVAL_REQUIRED, ARBITRATION_REQUIRED -> SessionStatus.WAITING_APPROVAL;
            case APPROVAL_RESOLVED, ARBITRATION_RESOLVED -> SessionStatus.RUNNING;
            case RUN_COMPLETED -> SessionStatus.COMPLETED;
            // 用户暂停、进程中断与真正失败分别显示；暂停仍是本轮终态，可以另起一轮续跑。
            case RUN_FAILED -> failedStatus(event);
            case RUN_CANCELLED -> SessionStatus.CANCELLED;
            default -> current;
        };
    }

    private static SessionStatus failedStatus(SessionEvent event) {
        if (event instanceof RunFailed failed) {
            if ("PAUSED".equals(failed.code())) return SessionStatus.PAUSED;
            if ("INTERRUPTED".equals(failed.code())) return SessionStatus.INTERRUPTED;
        }
        return SessionStatus.FAILED;
    }

    /**
     * 单个订阅者的有序投递器：有界队列 + 独立虚拟线程。
     *
     * <p>队列满、下游抛异常或显式 close 都会让这个订阅者停止工作并回调 {@code onClosed}，
     * 从而把「客户端跟不上」与「会话发布」彻底解耦。
     */
    private final class EventDelivery implements EventSubscription {
        private final Consumer<EventEnvelope> downstream;
        private final Runnable onClosed;
        private final BlockingQueue<EventEnvelope> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread worker;

        EventDelivery(Consumer<EventEnvelope> downstream, Runnable onClosed) {
            this.downstream = downstream;
            this.onClosed = onClosed;
            this.worker = Thread.ofVirtual().name("pilot-event-delivery").unstarted(this::drain);
            this.worker.start();
        }

        /** 非阻塞投递：满了就断开这个订阅者。 */
        void offer(EventEnvelope envelope) {
            if (closed.get()) {
                return;
            }
            if (!queue.offer(envelope)) {
                log.debug("订阅者积压超过 {} 条，断开连接", SUBSCRIBER_QUEUE_CAPACITY);
                close();
            }
        }

        /** 回放用：订阅瞬间同步写一次，保证「连上就能看到历史」。 */
        void deliverNow(EventEnvelope envelope) {
            if (closed.get()) {
                return;
            }
            try {
                downstream.accept(envelope);
            } catch (RuntimeException e) {
                log.debug("订阅者回放失败，按断开处理：{}", e.toString());
                close();
            }
        }

        private void drain() {
            try {
                while (!closed.get()) {
                    EventEnvelope envelope = queue.poll(1, TimeUnit.SECONDS);
                    if (envelope != null) {
                        downstream.accept(envelope);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.debug("订阅者投递失败，按断开处理：{}", e.toString());
            } finally {
                if (closed.compareAndSet(false, true)) {
                    onClosed.run();
                }
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                worker.interrupt();
                onClosed.run();
            }
        }
    }
}
