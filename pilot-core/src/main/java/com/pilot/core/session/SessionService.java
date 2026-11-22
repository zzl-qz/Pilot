package com.pilot.core.session;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.EventType;
import com.pilot.core.event.RunEnded;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SessionEvent;

/**
 * 会话服务：负责会话状态与历史写入，事件传输委托给 {@link SessionEventTransport}。
 *
 * <p>同一会话的「追加事件 + 推送订阅者」在同一把会话锁内完成，因此订阅者收到的事件顺序与
 * {@code sequence} 一致；订阅时捕获高水位并注册监听，锁外分页回放后衔接实时队列。
 *
 * <p>同一会话的历史写入与传输发布由会话锁排序；订阅者的有界队列和投递线程由 transport 管理。
 */
public final class SessionService implements AutoCloseable {
    private final Clock clock;
    private final SessionRepository repository;
    private final SessionEventTransport transport;
    private final java.util.concurrent.ConcurrentMap<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();
    /** 会话开停通知；默认空实现，装配方按需注册一个实例。 */
    private volatile SessionActivityListener activityListener = SessionActivityListener.NONE;

    public SessionService() {
        this(Clock.systemUTC(), new InMemorySessionRepository());
    }

    /** 注册会话开停通知（后注册的替换先前的）。 */
    public void setActivityListener(SessionActivityListener listener) {
        this.activityListener = listener == null ? SessionActivityListener.NONE : listener;
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

    public SessionService(Clock clock, SessionRepository repository, EventStreamLimits limits,
        PreviewLimits previewLimits) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transport = new SessionEventTransport(this.repository, Objects.requireNonNull(limits),
            Objects.requireNonNull(previewLimits));
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
        Session created = repository.create("ses_" + UUID.randomUUID(), title.strip(), normalizedWorkspace,
            clock.instant(), normalizedProject, isBlank, normalizedParent);
        activityListener.started(created);
        return created;
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
        if (!repository.setArchived(sessionId, archived)) {
            return false;
        }
        Optional<Session> session = repository.find(sessionId);
        if (session.isPresent()) {
            if (archived) {
                activityListener.stopped(session.get());
            } else {
                activityListener.started(session.get());
            }
        }
        return true;
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
            transport.finishPreview(sessionId, true);
            transport.clearSession(sessionId);
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
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            LongSupplier sequenceWithoutPending = () -> {
                Session session = repository.find(sessionId).orElse(null);
                if (session == null) {
                    locks.remove(sessionId, lock);
                    return 0L;
                }
                if (session.status().terminal()) {
                    return 0L;
                }
                var latest = repository.latestEvent(sessionId);
                if (latest.isEmpty() || !latest.get().runId().equals(runId)) {
                    return 0L;
                }
                return repository.latestSequence(sessionId);
            };
            transport.acceptPreview(sessionId, runId, event, clock.instant(), sequenceWithoutPending, task -> {
                synchronized (lock) {
                    task.run();
                }
            });
        }
    }

    private void finishPreview(String sessionId) {
        transport.finishPreview(sessionId, false);
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
            finishPreview(sessionId);
            SessionStatus status = nextStatus(current.status(), event);
            // 发过第一条消息就不再是空白对话。放在 publish 里，发送、重新生成、改历史重跑都覆盖
            if (event.type() == EventType.RUN_STARTED) {
                if (!current.titleManual() && (current.isBlank() || SessionTitles.placeholder(current.title()))) {
                    repository.autoRename(sessionId, SessionTitles.from(((RunStarted)event).input()));
                }
                repository.markNotBlank(sessionId);
            }
            EventEnvelope envelope = repository.append(sessionId, runId, clock.instant(), event, status);
            transport.eventAppended(envelope, status.terminal());
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
        return editHistory(sessionId, fromSequence, null, () -> {
        });
    }

    public long historyRevision(String sessionId) {
        return repository.historyRevision(sessionId);
    }

    public static final class HistoryChangedException extends RuntimeException {
        public HistoryChangedException() {
            super("会话历史已变化，请重新加载后操作");
        }
    }

    /** 其他数据库仓储写入与事件截断在同一事务内完成；提交后使所有旧订阅失效。 */
    public int editHistory(String sessionId, long fromSequence, Long expectedRevision, Runnable otherWrites) {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                throw new NoSuchSessionException(sessionId);
            }
            finishPreview(sessionId);
            int removed = repository.transaction(sessionId, () -> {
                if (expectedRevision != null && historyRevision(sessionId) != expectedRevision) {
                    throw new HistoryChangedException();
                }
                otherWrites.run();
                int count = repository.dropEventsFrom(sessionId, fromSequence);
                SessionStatus status = SessionStatus.CREATED;
                for (EventEnvelope event : repository.events(sessionId)) {
                    status = nextStatus(status, event.payload());
                }
                repository.updateStatus(sessionId, status);
                repository.advanceHistoryRevision(sessionId);
                return count;
            });
            transport.clearSession(sessionId);
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
        return subscribe(sessionId, afterSequence, expectedRevision, listener, references, null, onClosed);
    }

    public EventSubscription subscribe(String sessionId, long afterSequence, Long expectedRevision,
        Consumer<EventEnvelope> listener, Consumer<EventReference> references, Consumer<LiveOutput> snapshots,
        Runnable onClosed) {
        Objects.requireNonNull(listener, "listener must not be null");
        SessionEventTransport.Registration registration;
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            if (repository.find(sessionId).isEmpty()) {
                locks.remove(sessionId, lock);
                throw new NoSuchSessionException(sessionId);
            }
            finishPreview(sessionId);
            long revision = historyRevision(sessionId);
            if (expectedRevision != null && revision != expectedRevision) {
                throw new HistoryChangedException();
            }
            var latest = repository.latestEvent(sessionId);
            long highWater = latest.map(SessionRepository.EventPosition::sequence).orElse(0L);
            LiveOutput snapshot = snapshots == null ? null
                : transport.snapshot(sessionId, latest.map(SessionRepository.EventPosition::runId).orElse(""), highWater,
                    revision, highWater > 0 && !repository.find(sessionId).orElseThrow().status().terminal());
            registration = transport.register(sessionId, listener, references, onClosed, Math.max(0, afterSequence),
                highWater, revision, snapshots, snapshot);
        }
        // 注册与高水位捕获是短临界区；查询及所有回放发送均由锁外 worker 完成。
        registration.start();
        return registration.subscription();
    }

    /** 当前活跃订阅数，用于连接诊断与资源回归验证。 */
    public int subscriberCount() {
        return transport.subscriberCount();
    }

    public record StreamMetrics(int subscriptions, int workers, long queuedBytes, long peakQueuedBytes,
        long deliveredEvents, long replayNanos, long previewInputs, long previewOutputs, int pendingPreviews,
        long livePreviewBytes, int livePreviewSessions, int replayPages, int peakReplayPages,
        Map<String, Long> closeReasons) {
    }

    public StreamMetrics streamMetrics() {
        SessionEventTransport.Metrics metrics = transport.metrics();
        return new StreamMetrics(metrics.subscriptions(), metrics.workers(), metrics.queuedBytes(),
            metrics.peakQueuedBytes(), metrics.deliveredEvents(), metrics.replayNanos(), metrics.previewInputs(),
            metrics.previewOutputs(), metrics.pendingPreviews(), metrics.livePreviewBytes(),
            metrics.livePreviewSessions(), metrics.replayPages(), metrics.peakReplayPages(), metrics.closeReasons());
    }

    private Object lockFor(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return locks.computeIfAbsent(sessionId, key -> new Object());
    }

    private static SessionStatus nextStatus(SessionStatus current, SessionEvent event) {
        return switch (event.type()) {
            case RUN_STARTED -> SessionStatus.RUNNING;
            // 分歧 / 冲突 / 校验失败与工具审批一样，都是「等人做决定」
            case APPROVAL_REQUIRED, ARBITRATION_REQUIRED -> SessionStatus.WAITING_APPROVAL;
            case APPROVAL_RESOLVED, ARBITRATION_RESOLVED -> SessionStatus.RUNNING;
            case RUN_COMPLETED -> SessionStatus.COMPLETED;
            // 非失败终态：部分交付/阻塞/等用户不能伪装成红色失败。
            case RUN_ENDED -> endedStatus(event);
            // 用户暂停、进程中断与真正失败分别显示；暂停仍是本轮终态，可以另起一轮续跑。
            case RUN_FAILED -> failedStatus(event);
            case RUN_CANCELLED -> SessionStatus.CANCELLED;
            default -> current;
        };
    }

    private static SessionStatus failedStatus(SessionEvent event) {
        if (event instanceof RunFailed failed) {
            if (RunFailureCode.PAUSED.is(failed.code())) {
                return SessionStatus.PAUSED;
            }
            if (RunFailureCode.INTERRUPTED.is(failed.code())) {
                return SessionStatus.INTERRUPTED;
            }
        }
        return SessionStatus.FAILED;
    }

    /** 非失败终态的会话状态：只有真正完成才 COMPLETED，其余可继续的落 INTERRUPTED。 */
    private static SessionStatus endedStatus(SessionEvent event) {
        if (!(event instanceof RunEnded ended)) {
            return SessionStatus.INTERRUPTED;
        }
        return switch (ended.outcome()) {
            case COMPLETED -> SessionStatus.COMPLETED;
            case PAUSED -> SessionStatus.PAUSED;
            case CANCELLED -> SessionStatus.CANCELLED;
            case FAILED -> SessionStatus.FAILED;
            default -> SessionStatus.INTERRUPTED;
        };
    }

    @Override
    public void close() {
        transport.close();
    }

}
