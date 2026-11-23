package com.pilot.core.coordination;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 工作区协调事件的发布、有界回放与订阅分发。
 *
 * <p>这是实例服务，不依赖静态状态：缓存、序号与订阅都挂在对象上，由依赖注入方决定生命周期。
 * 发布在历史锁内完成「分配序号 + 写入缓存」，因此回放顺序与发布顺序一致；
 * 每个订阅有自己的有界队列与投递线程，跟不上的订阅被关闭而不是拖慢发布。
 */
public final class WorkspaceCoordinationEventBus
    implements WorkspaceEventPublisher, WorkspaceEventReplay, WorkspaceEventFeed, AutoCloseable {

    private static final String REASON_CLOSED = "closed";
    private static final String REASON_QUEUE_OVERFLOW = "queue_overflow";
    private static final String REASON_DELIVERY_ERROR = "delivery_error";

    private final WorkspaceEventLimits limits;
    private final Clock clock;
    private final Object historyLock = new Object();
    private final ArrayDeque<WorkspaceCoordinationEvent> history = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong deliveryIds = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final ConcurrentMap<String, Long> closeReasons = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, WorkspaceEventDelivery> deliveries = new ConcurrentHashMap<>();
    private long historyBytes;

    public WorkspaceCoordinationEventBus() {
        this(WorkspaceEventLimits.defaults(), Clock.systemUTC());
    }

    public WorkspaceCoordinationEventBus(WorkspaceEventLimits limits) {
        this(limits, Clock.systemUTC());
    }

    public WorkspaceCoordinationEventBus(WorkspaceEventLimits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** 发布过程中的统计，便于排查订阅关闭与缓存丢弃。 */
    public record Metrics(long published, int subscriptions, long deliveredEvents, long droppedEvents,
        Map<String, Long> closeReasons) {
    }

    @Override
    public WorkspaceCoordinationEvent publish(WorkspaceEventDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        WorkspaceCoordinationEvent event;
        synchronized (historyLock) {
            long next = sequence.incrementAndGet();
            Instant timestamp = clock.instant();
            event = new WorkspaceCoordinationEvent(next, timestamp, draft.type(), draft.projectId(), draft.sessionId(),
                draft.directory(), draft.subject(), draft.details()).compacted(limits.maxEventBytes());
            history.addLast(event);
            historyBytes += event.estimatedBytes();
            while (history.size() > 1 && (history.size() > limits.maxEvents() || historyBytes > limits.maxBytes())) {
                WorkspaceCoordinationEvent oldest = history.pollFirst();
                if (oldest == null) {
                    break;
                }
                historyBytes -= oldest.estimatedBytes();
                dropped.incrementAndGet();
            }
            // 分发也在锁内：入队是非阻塞的，这样每个订阅看到的事件顺序等于发布顺序。
            deliveries.values().forEach(delivery -> delivery.offer(event));
        }
        return event;
    }

    @Override
    public List<WorkspaceCoordinationEvent> replay(long afterSequence, ProjectCoordinationScope scope, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<WorkspaceCoordinationEvent> page = new ArrayList<>();
        synchronized (historyLock) {
            for (WorkspaceCoordinationEvent event : history) {
                if (event.sequence() <= afterSequence || !event.visibleTo(scope)) {
                    continue;
                }
                page.add(event);
                if (page.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(page);
    }

    @Override
    public long latestSequence() {
        return sequence.get();
    }

    @Override
    public long earliestRetainedSequence() {
        synchronized (historyLock) {
            WorkspaceCoordinationEvent oldest = history.peekFirst();
            return oldest == null ? sequence.get() : oldest.sequence();
        }
    }

    @Override
    public WorkspaceEventSubscription subscribe(ProjectCoordinationScope scope, long afterSequence,
        Consumer<WorkspaceCoordinationEvent> listener) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        WorkspaceEventDelivery delivery =
            new WorkspaceEventDelivery(deliveryIds.incrementAndGet(), scope, Math.max(0, afterSequence), listener);
        synchronized (deliveries) {
            if (deliveries.size() >= limits.maxSubscribers()) {
                throw new IllegalStateException("工作区事件订阅数量已达上限");
            }
            deliveries.put(delivery.id, delivery);
        }
        delivery.start();
        return delivery;
    }

    @Override
    public int subscriptionCount() {
        return deliveries.size();
    }

    public Metrics metrics() {
        Map<String, Long> reasons = new TreeMap<>();
        closeReasons.forEach((key, value) -> reasons.put(key, value));
        return new Metrics(sequence.get(), subscriptionCount(), delivered.get(), dropped.get(), Map.copyOf(reasons));
    }

    @Override
    public void close() {
        deliveries.values().forEach(delivery -> delivery.close(REASON_CLOSED));
    }

    private void unsubscribe(WorkspaceEventDelivery delivery) {
        deliveries.remove(delivery.id, delivery);
        closeReasons.merge(delivery.closeReason(), 1L, Long::sum);
    }

    /** 一个订阅：有界队列 + 独立投递线程，历史补齐后再消费实时事件，按序号去重。 */
    private final class WorkspaceEventDelivery implements WorkspaceEventSubscription {
        private final long id;
        private final ProjectCoordinationScope scope;
        private final Consumer<WorkspaceCoordinationEvent> listener;
        private final BlockingQueue<WorkspaceCoordinationEvent> queue = new ArrayBlockingQueue<>(limits.queueItems());
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong cursor;
        private final AtomicLong deliveredCount = new AtomicLong();
        private volatile String closeReason = REASON_CLOSED;
        private Thread worker;

        WorkspaceEventDelivery(long id, ProjectCoordinationScope scope, long afterSequence,
            Consumer<WorkspaceCoordinationEvent> listener) {
            this.id = id;
            this.scope = scope;
            this.listener = listener;
            this.cursor = new AtomicLong(afterSequence);
        }

        void start() {
            worker = Thread.ofVirtual().name("workspace-event-delivery").unstarted(this::run);
            worker.start();
        }

        void offer(WorkspaceCoordinationEvent event) {
            if (closed.get() || !event.visibleTo(scope) || event.sequence() <= cursor.get()) {
                return;
            }
            if (!queue.offer(event)) {
                close(REASON_QUEUE_OVERFLOW);
            }
        }

        private void run() {
            try {
                replayHistory();
                while (!closed.get()) {
                    WorkspaceCoordinationEvent event = queue.poll(1, TimeUnit.SECONDS);
                    if (event != null) {
                        deliver(event);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                close(REASON_DELIVERY_ERROR);
                return;
            }
            close(REASON_CLOSED);
        }

        private void replayHistory() {
            while (!closed.get()) {
                List<WorkspaceCoordinationEvent> page = replay(cursor.get(), scope, limits.replayPageSize());
                for (WorkspaceCoordinationEvent event : page) {
                    if (closed.get()) {
                        return;
                    }
                    deliver(event);
                }
                if (page.size() < limits.replayPageSize()) {
                    return;
                }
            }
        }

        private void deliver(WorkspaceCoordinationEvent event) {
            if (event.sequence() <= cursor.get()) {
                return;
            }
            listener.accept(event);
            cursor.set(event.sequence());
            deliveredCount.incrementAndGet();
            delivered.incrementAndGet();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public String closeReason() {
            return closeReason;
        }

        @Override
        public long cursor() {
            return cursor.get();
        }

        @Override
        public long deliveredCount() {
            return deliveredCount.get();
        }

        @Override
        public void close() {
            close(REASON_CLOSED);
        }

        void close(String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeReason = reason;
            queue.clear();
            Thread current = worker;
            if (current != null && current != Thread.currentThread()) {
                current.interrupt();
            }
            unsubscribe(this);
        }
    }
}
