package com.pilot.core.session;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.EventType;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SessionEvent;

/**
 * 会话服务：负责 id 生成、状态推进与事件订阅，数据读写委托给 {@link SessionRepository}。
 *
 * <p>同一会话的「追加事件 + 推送订阅者」在同一把会话锁内完成，因此订阅者收到的事件顺序与
 * {@code sequence} 一致；订阅时捕获高水位并注册监听，锁外分页回放后衔接实时队列。
 *
 * <p>推送不在锁内直接写连接：每个订阅者有一个有界队列和独立投递线程。慢客户端只会把自己
 * 的队列填满并被断开（断线后按游标重连即可补齐），不会拖住会话的发布线程和 Agent 循环。
 */
public final class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    /** 单个订阅者允许积压的事件数。超过即视为客户端跟不上，直接断开。 */
    private static final java.util.concurrent.ScheduledExecutorService MONITOR =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pilot-event-monitor"); thread.setDaemon(true); return thread;
        });

    private final Clock clock;
    private final SessionRepository repository;
    private final EventStreamLimits limits;
    private final PreviewLimits previewLimits;
    private final ConcurrentMap<String, PendingPreview> previews = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingPreviews = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong previewInputs = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong previewOutputs = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger workers = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong queuedBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong peakBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong delivered = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong replayNanos = new java.util.concurrent.atomic.AtomicLong();
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong> closedReasons = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<EventDelivery>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public SessionService() {
        this(Clock.systemUTC(), new InMemorySessionRepository());
    }

    public SessionService(Clock clock) {
        this(clock, new InMemorySessionRepository());
    }

    public SessionService(Clock clock, SessionRepository repository) {
        this(clock, repository, EventStreamLimits.defaults());
    }

    public SessionService(Clock clock, SessionRepository repository, EventStreamLimits limits) {
        this(clock, repository, limits, PreviewLimits.defaults());
    }

    public SessionService(Clock clock, SessionRepository repository, EventStreamLimits limits, PreviewLimits previewLimits) {
        this.previewLimits = Objects.requireNonNull(previewLimits);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.limits = Objects.requireNonNull(limits);
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
        return createSession(title, workspace, projectId, isBlank, null);
    }

    /**
     * @param parentSessionId 侧边对话的父会话；普通对话传 {@code null}
     */
    public Session createSession(String title, String workspace, String projectId, boolean isBlank,
        String parentSessionId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String normalizedWorkspace = workspace == null || workspace.isBlank() ? null : workspace.strip();
        String normalizedProject = projectId == null || projectId.isBlank() ? null : projectId.strip();
        String normalizedParent = parentSessionId == null || parentSessionId.isBlank() ? null : parentSessionId.strip();
        return repository.create("ses_" + UUID.randomUUID(), title.strip(), normalizedWorkspace, clock.instant(),
            normalizedProject, isBlank, normalizedParent);
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

    /** 置顶优先，其余按最近活动时间倒序返回全部会话。 */
    public List<Session> list() {
        return repository.list();
    }

    /** 某个项目下的会话，置顶优先、按最近活动时间倒序。 */
    public List<Session> listByProject(String projectId) {
        return repository.listByProject(projectId);
    }

    public boolean rename(String sessionId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return repository.rename(sessionId, title.strip());
    }

    public boolean updatePreferences(String sessionId, Boolean pinned, Boolean unread) {
        return repository.updatePreferences(sessionId, pinned, unread);
    }

    public boolean touch(String sessionId) {
        return repository.touch(sessionId, clock.instant());
    }

    /** 删除会话：先摘掉订阅者再删数据，避免删除后仍有推送。 */
    public boolean delete(String sessionId) {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            discardPreview(sessionId);
            CopyOnWriteArrayList<EventDelivery> existing = subscribers.remove(sessionId);
            if (existing != null) {
                existing.forEach(EventDelivery::close);
            }
            boolean deleted = repository.delete(sessionId);
            locks.remove(sessionId, lock);
            return deleted;
        }
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
        String text;
        boolean reset;
        if (event instanceof com.pilot.core.event.AssistantPreview part) { text = part.text(); reset = part.reset(); }
        else if (event instanceof com.pilot.core.event.AssistantReasoning part) { text = part.text(); reset = part.reset(); }
        else throw new IllegalArgumentException("preview 只接收正文或推理增量");
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            PendingPreview pending = previews.get(sessionId);
            if (pending == null && repository.find(sessionId).isEmpty()) { locks.remove(sessionId, lock); return; }
            previewInputs.incrementAndGet();
            if (pending != null && (!pending.runId.equals(runId) || pending.type != event.type() || reset)) {
                flushPreview(sessionId, pending); pending = null;
            }
            long sequence = pending == null ? repository.latestSequence(sessionId) : pending.sequence;
            if (sequence < 1) return;
            if (reset) { deliverPreview(sessionId, runId, sequence, event); return; }
            for (int i = 0; i < text.length();) {
                int point = text.codePointAt(i);
                int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
                if (pending != null && pending.bytes + size > previewLimits.mergeBytes()) { flushPreview(sessionId, pending); pending = null; }
                if (pending == null) {
                    if (pendingPreviews.incrementAndGet() > previewLimits.sessions()) {
                        pendingPreviews.decrementAndGet();
                        SessionEvent remaining = event.type() == EventType.ASSISTANT_PREVIEW
                            ? new com.pilot.core.event.AssistantPreview(text.substring(i)) : new com.pilot.core.event.AssistantReasoning(text.substring(i));
                        deliverPreview(sessionId, runId, sequence, remaining); return;
                    }
                    pending = new PendingPreview(runId, event.type(), sequence);
                    previews.put(sessionId, pending);
                    PendingPreview expected = pending;
                    pending.timer = MONITOR.schedule(() -> Thread.ofVirtual().name("pilot-preview-flush").start(() -> {
                        synchronized (lock) { if (previews.get(sessionId) == expected) flushPreview(sessionId, expected); }
                    }), previewLimits.mergeMillis(), TimeUnit.MILLISECONDS);
                }
                pending.text.appendCodePoint(point); pending.bytes += size; i += Character.charCount(point);
                if (pending.bytes >= previewLimits.mergeBytes()) { flushPreview(sessionId, pending); pending = null; }
            }
        }
    }

    private static final class PendingPreview {
        final String runId; final EventType type; final long sequence;
        final StringBuilder text = new StringBuilder(); int bytes;
        java.util.concurrent.ScheduledFuture<?> timer;
        PendingPreview(String runId, EventType type, long sequence) { this.runId = runId; this.type = type; this.sequence = sequence; }
    }
    private void discardPreview(String sessionId) {
        PendingPreview pending = previews.remove(sessionId);
        if (pending != null) { pendingPreviews.decrementAndGet(); if (pending.timer != null) pending.timer.cancel(false); }
    }
    private void flushPreview(String sessionId, PendingPreview pending) {
        if (pending == null || previews.get(sessionId) != pending) return;
        discardPreview(sessionId);
        SessionEvent event = pending.type == EventType.ASSISTANT_PREVIEW
            ? new com.pilot.core.event.AssistantPreview(pending.text.toString())
            : new com.pilot.core.event.AssistantReasoning(pending.text.toString());
        deliverPreview(sessionId, pending.runId, pending.sequence, event);
    }
    private void deliverPreview(String sessionId, String runId, long sequence, SessionEvent event) {
        previewOutputs.incrementAndGet();
        deliver(sessionId, EventEnvelope.of(sequence, clock.instant(), sessionId, runId, event), EventSize.estimate(event) + 1024);
    }

    /** 追加事件并推送给当前订阅者，返回带序号与时间戳的信封。 */
    public EventEnvelope publish(String sessionId, String runId, SessionEvent event) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(event, "event must not be null");
        long size = EventSize.estimate(event) + 1024;
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            Session current = repository.find(sessionId).orElseGet(() -> {
                // 往不存在的会话发布事件：顺手清掉刚建的锁，避免 map 只增不减
                locks.remove(sessionId, lock);
                throw new NoSuchSessionException(sessionId);
            });
            flushPreview(sessionId, previews.get(sessionId));
            SessionStatus status = nextStatus(current.status(), event);
            // 发过第一条消息就不再是空白对话。放在 publish 里，发送、重新生成、改历史重跑都覆盖
            if (event.type() == EventType.RUN_STARTED) {
                if (!current.titleManual() && (current.isBlank() || SessionTitles.placeholder(current.title()))) {
                    repository.autoRename(sessionId, SessionTitles.from(((RunStarted)event).input()));
                }
                repository.markNotBlank(sessionId);
            }
            EventEnvelope envelope = repository.append(sessionId, runId, clock.instant(), event, status);
            deliver(sessionId, envelope, size);
            return envelope;
        }
    }

    /** 投递不阻塞：队列满说明客户端已经跟不上，摘掉它即可，历史事件还能按游标补。 */
    private void deliver(String sessionId, EventEnvelope envelope, long size) {
        CopyOnWriteArrayList<EventDelivery> current = subscribers.get(sessionId);
        if (current == null) {
            return;
        }
        for (EventDelivery delivery : current) {
            delivery.offer(envelope, size);
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

    public List<EventEnvelope> replayPage(String sessionId, long after, long through, int limit) {
        return repository.replayPage(sessionId, after, through, limit);
    }

    public long latestSequence(String sessionId) {
        return repository.latestSequence(sessionId);
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

    public List<EventEnvelope> eventsPageAfter(String sessionId, long after, int limit) {
        return repository.eventsPageAfter(sessionId, after, limit);
    }

    /**
     * 从某条用户消息起重写对话时，把这条以及后面的事件删掉。
     * 和 {@link #publish} 共用会话锁，避免删到一半又被追加。
     */
    public int dropEventsFrom(String sessionId, long fromSequence) {
        return editHistory(sessionId, fromSequence, null, () -> {});
    }

    public long historyRevision(String sessionId) { return repository.historyRevision(sessionId); }

    public static final class HistoryChangedException extends RuntimeException {
        public HistoryChangedException() { super("会话历史已变化，请重新加载后操作"); }
    }

    /** 其他数据库仓储写入与事件截断在同一事务内完成；提交后使所有旧订阅失效。 */
    public int editHistory(String sessionId, long fromSequence, Long expectedRevision, Runnable otherWrites) {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                throw new NoSuchSessionException(sessionId);
            }
            flushPreview(sessionId, previews.get(sessionId));
            int removed = repository.transaction(sessionId, () -> {
                if (expectedRevision != null && historyRevision(sessionId) != expectedRevision) {
                    throw new HistoryChangedException();
                }
                otherWrites.run();
                int count = repository.dropEventsFrom(sessionId, fromSequence);
                SessionStatus status = SessionStatus.CREATED;
                for (EventEnvelope event : repository.events(sessionId)) status = nextStatus(status, event.payload());
                repository.updateStatus(sessionId, status);
                repository.advanceHistoryRevision(sessionId);
                return count;
            });
            var current = subscribers.get(sessionId);
            if (current != null) current.forEach(EventDelivery::close);
            return removed;
        }
    }

    /**
     * 订阅会话事件：先按顺序回放 {@code afterSequence} 之后的历史事件，再接收后续新事件。
     *
     * @throws NoSuchSessionException 会话不存在
     */
    public EventSubscription subscribe(String sessionId, long afterSequence, Consumer<EventEnvelope> listener) {
        return subscribe(sessionId, afterSequence, listener, () -> {
        });
    }

    /**
     * @param onClosed 订阅被动结束（客户端断开、队列积压溢出）时的回调，用于关闭底层连接
     */
    public EventSubscription subscribe(String sessionId, long afterSequence, Consumer<EventEnvelope> listener,
        Runnable onClosed) {
        return subscribe(sessionId, afterSequence, null, listener, onClosed);
    }

    public EventSubscription subscribe(String sessionId, long afterSequence, Long expectedRevision,
        Consumer<EventEnvelope> listener, Runnable onClosed) {
        return subscribe(sessionId, afterSequence, expectedRevision, listener, null, onClosed);
    }

    public EventSubscription subscribe(String sessionId, long afterSequence, Long expectedRevision,
        Consumer<EventEnvelope> listener, Consumer<EventReference> references, Runnable onClosed) {
        Objects.requireNonNull(listener, "listener must not be null");
        EventDelivery delivery;
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                locks.remove(sessionId, lock);
                throw new NoSuchSessionException(sessionId);
            }
            flushPreview(sessionId, previews.get(sessionId));
            long revision = historyRevision(sessionId);
            if (expectedRevision != null && revision != expectedRevision) throw new HistoryChangedException();
            if (listeners(sessionId).size() >= limits.subscribersPerSession()) throw new IllegalStateException("会话订阅数量已达上限");
            if (workers.incrementAndGet() > limits.subscribers()) {
                workers.decrementAndGet(); throw new IllegalStateException("事件流连接数量已达上限");
            }
            delivery = new EventDelivery(sessionId, listener, references, onClosed, Math.max(0, afterSequence),
                repository.latestSequence(sessionId), revision);
            listeners(sessionId).add(delivery);
        }
        // 注册与高水位捕获是短临界区；查询及所有回放发送均由锁外 worker 完成。
        delivery.start();
        return delivery;
    }

    /** 当前活跃订阅数，用于连接诊断与资源回归验证。 */
    public int subscriberCount() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    public record StreamMetrics(int subscriptions, int workers, long queuedBytes, long peakQueuedBytes,
        long deliveredEvents, long replayNanos, long previewInputs, long previewOutputs, int pendingPreviews,
        java.util.Map<String, Long> closeReasons) {}
    public StreamMetrics streamMetrics() {
        java.util.Map<String, Long> reasons = new java.util.TreeMap<>();
        closedReasons.forEach((key, value) -> reasons.put(key, value.get()));
        return new StreamMetrics(subscriberCount(), workers.get(), queuedBytes.get(), peakBytes.get(),
            delivered.get(), replayNanos.get(), previewInputs.get(), previewOutputs.get(), pendingPreviews.get(), java.util.Map.copyOf(reasons));
    }

    private boolean reserve(long size) {
        long current;
        do { current = queuedBytes.get(); if (size > limits.totalBytes() - current) return false; }
        while (!queuedBytes.compareAndSet(current, current + size));
        peakBytes.accumulateAndGet(current + size, Math::max);
        return true;
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
            if ("PAUSED".equals(failed.code())) {
                return SessionStatus.PAUSED;
            }
            if ("INTERRUPTED".equals(failed.code())) {
                return SessionStatus.INTERRUPTED;
            }
        }
        return SessionStatus.FAILED;
    }

    /**
     * 单个订阅者的有序投递器：有界队列 + 独立虚拟线程。
     *
     * <p>队列满、下游抛异常或显式 close 都会让这个订阅者停止工作并回调 {@code onClosed}，
     * 从而把「客户端跟不上」与「会话发布」彻底解耦。
     */
    private record PendingEvent(EventEnvelope event, EventReference reference, long bytes, long enqueued) {}

    private final class EventDelivery implements EventSubscription {
        private final String sessionId;
        private final Consumer<EventEnvelope> downstream;
        private final Consumer<EventReference> references;
        private final Runnable onClosed;
        private final BlockingQueue<PendingEvent> queue = new ArrayBlockingQueue<>(limits.queueItems());
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object budget = new Object();
        private final Thread worker;
        private final long afterSequence, highWater, revision;
        private volatile long sendingAt;
        private volatile boolean replaying = true;
        private volatile String reason = "closed";
        private volatile java.util.concurrent.ScheduledFuture<?> monitor;
        private long bytes;

        EventDelivery(String sessionId, Consumer<EventEnvelope> downstream, Consumer<EventReference> references,
            Runnable onClosed, long afterSequence, long highWater, long revision) {
            this.afterSequence = afterSequence; this.highWater = highWater; this.revision = revision;
            this.sessionId = sessionId; this.downstream = downstream; this.references = references; this.onClosed = onClosed;
            this.worker = Thread.ofVirtual().name("pilot-event-delivery").unstarted(this::drain);
        }
        void start() {
            long started = System.nanoTime();
            monitor = MONITOR.scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                PendingEvent oldest = queue.peek();
                if (sendingAt != 0 && now - sendingAt > TimeUnit.MILLISECONDS.toNanos(limits.sendMillis())) close("send_timeout");
                else if (oldest != null && now - oldest.enqueued() > TimeUnit.MILLISECONDS.toNanos(limits.queueWaitMillis())) close("queue_timeout");
                else if (replaying && now - started > TimeUnit.MILLISECONDS.toNanos(limits.replayMillis())) close("replay_timeout");
            }, 50, 50, TimeUnit.MILLISECONDS);
            if (closed.get()) monitor.cancel(false);
            worker.start();
        }
        private PendingEvent pending(EventEnvelope event, long size) {
            boolean ephemeral = event.payload().type() == EventType.ASSISTANT_PREVIEW || event.payload().type() == EventType.ASSISTANT_REASONING;
            if (!ephemeral && references != null && size > limits.referenceBytes()) {
                return new PendingEvent(null, new EventReference(event.sequence(), event.sessionId(), event.runId(), event.type()), 512, System.nanoTime());
            }
            return new PendingEvent(event, null, size, System.nanoTime());
        }
        void offer(EventEnvelope event, long size) {
            PendingEvent pending = pending(event, size);
            boolean accepted = false;
            synchronized (budget) {
                if (closed.get()) return;
                if (bytes + pending.bytes() <= limits.queueBytes() && queue.remainingCapacity() > 0 && reserve(pending.bytes())) {
                    bytes += pending.bytes(); queue.add(pending); accepted = true;
                }
            }
            if (!accepted) close("queue_budget");
        }
        private void release(PendingEvent pending) {
            synchronized (budget) { bytes -= pending.bytes(); queuedBytes.addAndGet(-pending.bytes()); }
        }
        private void send(PendingEvent pending) {
            if (closed.get()) return;
            sendingAt = System.nanoTime();
            try {
                if (pending.reference() != null) references.accept(pending.reference()); else downstream.accept(pending.event());
                delivered.incrementAndGet();
            } finally { sendingAt = 0; }
        }
        private void drain() {
            long began = System.nanoTime();
            try {
                long cursor = afterSequence;
                while (!closed.get() && cursor < highWater) {
                    if (historyRevision(sessionId) != revision) { close("history_changed"); return; }
                    List<EventEnvelope> page = repository.replayPage(sessionId, cursor, highWater, limits.pageSize());
                    if (page.isEmpty()) break;
                    for (EventEnvelope event : page) {
                        if (closed.get() || historyRevision(sessionId) != revision) return;
                        PendingEvent pending = pending(event, EventSize.estimate(event.payload()) + 1024);
                        synchronized (budget) {
                            if (pending.bytes() + bytes > limits.queueBytes() || !reserve(pending.bytes())) { close("queue_budget"); return; }
                            bytes += pending.bytes();
                        }
                        try { send(pending); } finally { release(pending); }
                        cursor = event.sequence();
                    }
                }
                replaying = false; replayNanos.addAndGet(System.nanoTime() - began);
                if (historyRevision(sessionId) != revision) { close("history_changed"); return; }
                while (!closed.get()) {
                    PendingEvent pending = queue.poll(1, TimeUnit.SECONDS);
                    if (pending != null) { try { send(pending); } finally { release(pending); } }
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                log.debug("订阅交付失败：{}", failure.getClass().getSimpleName());
                close(failure instanceof EventTooLargeException ? "event_too_large" : "delivery_error");
            } finally { close(); workers.decrementAndGet(); }
        }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public String closeReason() { return reason; }
        @Override public void close() { close("closed"); }
        private void close(String why) {
            if (!closed.compareAndSet(false, true)) return;
            reason = why;
            unsubscribe(sessionId, this);
            synchronized (budget) {
                PendingEvent pending;
                while ((pending = queue.poll()) != null) release(pending);
            }
            var timer = monitor; if (timer != null) timer.cancel(false);
            worker.interrupt();
            closedReasons.computeIfAbsent(why, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            if (onClosed != null) Thread.ofVirtual().name("pilot-event-close").start(() -> {
                try { onClosed.run(); } catch (RuntimeException failure) { log.debug("订阅关闭回调失败", failure); }
            });
        }
    }
}
