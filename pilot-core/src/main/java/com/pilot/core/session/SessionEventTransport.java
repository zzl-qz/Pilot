package com.pilot.core.session;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.EventType;
import com.pilot.core.event.SessionEvent;

import lombok.extern.slf4j.Slf4j;

/** 会话事件的回放、实时投递与临时输出传输状态。 */
@Slf4j
final class SessionEventTransport implements AutoCloseable {
    record Metrics(int subscriptions, int workers, long queuedBytes, long peakQueuedBytes, long deliveredEvents,
        long replayNanos, long previewInputs, long previewOutputs, int pendingPreviews, long livePreviewBytes,
        int livePreviewSessions, int replayPages, int peakReplayPages, Map<String, Long> closeReasons) {
    }

    private final SessionRepository repository;
    private final EventStreamLimits limits;
    private final LiveOutputCache liveOutputs;
    private final ScheduledExecutorService monitor;
    private final SessionPreviewAggregator previews;
    private final Semaphore replaySlots;
    private final AtomicInteger replayPages = new AtomicInteger();
    private final AtomicInteger peakReplayPages = new AtomicInteger();
    private final AtomicLong previewInputs = new AtomicLong();
    private final AtomicLong previewOutputs = new AtomicLong();
    private final AtomicInteger workers = new AtomicInteger();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong peakBytes = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong replayNanos = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> closedReasons = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<EventDelivery>> subscribers = new ConcurrentHashMap<>();

    SessionEventTransport(SessionRepository repository, EventStreamLimits limits, PreviewLimits previewLimits) {
        this.repository = repository;
        this.limits = limits;
        this.monitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pilot-event-monitor");
            thread.setDaemon(true);
            return thread;
        });
        this.previews = new SessionPreviewAggregator(previewLimits, monitor);
        this.liveOutputs = new LiveOutputCache(previewLimits);
        this.replaySlots = new Semaphore(Math.clamp(limits.totalBytes() / (16L * 1024 * 1024), 1, 16), true);
    }

    void acceptPreview(String sessionId, String runId, SessionEvent event, java.time.Instant timestamp,
        LongSupplier sequenceWithoutPending, Consumer<Runnable> sessionLock) {
        String text;
        boolean reset;
        if (event instanceof AssistantPreview part) {
            text = part.text();
            reset = part.reset();
        } else if (event instanceof AssistantReasoning part) {
            text = part.text();
            reset = part.reset();
        } else {
            throw new IllegalArgumentException("preview 只接收正文或推理增量");
        }
        var update = previews.accept(new SessionPreviewAggregator.PreviewInput(sessionId, runId, event.type(), text,
            reset, timestamp), sequenceWithoutPending, this::deliverPreview,
            task -> Thread.ofVirtual().name("pilot-preview-flush").start(() -> sessionLock.accept(task)));
        if (update.accepted()) {
            previewInputs.incrementAndGet();
        }
    }

    void finishPreview(String sessionId, boolean discard) {
        previews.finish(sessionId, this::deliverPreview, discard);
    }

    void eventAppended(EventEnvelope envelope, boolean terminal) {
        if (envelope.payload().type() == EventType.RUN_STARTED) {
            liveOutputs.begin(envelope.sessionId(), envelope.runId());
        } else if (envelope.payload().type() == EventType.ASSISTANT_DELTA) {
            liveOutputs.commit(envelope.sessionId(), envelope.runId(), envelope.timestamp());
        }
        if (terminal) {
            liveOutputs.clear(envelope.sessionId());
        }
        deliver(envelope.sessionId(), envelope, EventSize.estimate(envelope.payload()) + 1024);
    }

    void clearSession(String sessionId) {
        liveOutputs.clear(sessionId);
        CopyOnWriteArrayList<EventDelivery> current = subscribers.get(sessionId);
        if (current != null) {
            current.forEach(EventDelivery::close);
        }
    }

    LiveOutput snapshot(String sessionId, String runId, long sequence, long revision, boolean active) {
        return liveOutputs.snapshot(sessionId, runId, sequence, revision, active);
    }

    Registration register(String sessionId, Consumer<EventEnvelope> listener, Consumer<EventReference> references,
        Runnable onClosed, long afterSequence, long highWater, long revision, Consumer<LiveOutput> snapshots,
        LiveOutput snapshot) {
        if (listeners(sessionId).size() >= limits.subscribersPerSession()) {
            throw new IllegalStateException("会话订阅数量已达上限");
        }
        if (workers.incrementAndGet() > limits.subscribers()) {
            workers.decrementAndGet();
            throw new IllegalStateException("事件流连接数量已达上限");
        }
        EventDelivery delivery;
        try {
            delivery = new EventDelivery(sessionId, listener, references, onClosed, afterSequence, highWater, revision,
                snapshots, snapshot);
        } catch (RuntimeException failure) {
            workers.decrementAndGet();
            throw failure;
        }
        listeners(sessionId).add(delivery);
        return new Registration(delivery);
    }

    int subscriberCount() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    Metrics metrics() {
        Map<String, Long> reasons = new TreeMap<>();
        closedReasons.forEach((key, value) -> reasons.put(key, value.get()));
        return new Metrics(subscriberCount(), workers.get(), queuedBytes.get(), peakBytes.get(),
            delivered.get(), replayNanos.get(), previewInputs.get(), previewOutputs.get(), previews.pendingSessions(),
            liveOutputs.bytes(), liveOutputs.size(), replayPages.get(), peakReplayPages.get(), Map.copyOf(reasons));
    }

    private void deliverPreview(SessionPreviewAggregator.PreviewOutput preview) {
        SessionEvent event = preview.type() == EventType.ASSISTANT_PREVIEW
            ? new AssistantPreview(preview.text(), preview.reset())
            : new AssistantReasoning(preview.text(), preview.reset());
        previewOutputs.incrementAndGet();
        EventEnvelope envelope = liveOutputs.update(EventEnvelope.of(preview.sequence(), preview.timestamp(),
            preview.sessionId(), preview.runId(), event));
        deliver(preview.sessionId(), envelope, EventSize.estimate(event) + 1024);
    }

    private void deliver(String sessionId, EventEnvelope envelope, long size) {
        CopyOnWriteArrayList<EventDelivery> current = subscribers.get(sessionId);
        if (current != null) {
            current.forEach(delivery -> delivery.offer(envelope, size));
        }
    }

    private boolean reserve(long size) {
        long current;
        do {
            current = queuedBytes.get();
            if (size > limits.totalBytes() - current) {
                return false;
            }
        } while (!queuedBytes.compareAndSet(current, current + size));
        peakBytes.accumulateAndGet(current + size, Math::max);
        return true;
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

    @Override
    public void close() {
        subscribers.values().forEach(deliveries -> deliveries.forEach(EventDelivery::close));
        monitor.shutdownNow();
    }

    final class Registration {
        private final EventDelivery delivery;

        private Registration(EventDelivery delivery) {
            this.delivery = delivery;
        }

        EventSubscription subscription() {
            return delivery;
        }

        void start() {
            delivery.start();
        }
    }

    private record PendingEvent(EventEnvelope event, EventReference reference, long bytes, long enqueued) {
    }

    private final class EventDelivery implements EventSubscription {
        private final String sessionId;
        private final Consumer<EventEnvelope> downstream;
        private final Consumer<EventReference> references;
        private final Consumer<LiveOutput> snapshots;
        private LiveOutput snapshot;
        private long snapshotBytes;
        private final Runnable onClosed;
        private final BlockingQueue<PendingEvent> queue = new ArrayBlockingQueue<>(limits.queueItems());
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object budget = new Object();
        private final Thread worker;
        private final long afterSequence;
        private final long highWater;
        private final long revision;
        private volatile long sendingAt;
        private long revisionCheckedAt;
        private volatile boolean replaying = true;
        private volatile String reason = "closed";
        private volatile java.util.concurrent.ScheduledFuture<?> timer;
        private long bytes;

        EventDelivery(String sessionId, Consumer<EventEnvelope> downstream, Consumer<EventReference> references,
            Runnable onClosed, long afterSequence, long highWater, long revision, Consumer<LiveOutput> snapshots,
            LiveOutput snapshot) {
            this.snapshots = snapshots;
            this.snapshot = snapshot;
            if (snapshot != null) {
                snapshotBytes = 1024 + 6L * (snapshot.text().length() + snapshot.reasoning().length());
                if (snapshotBytes > limits.queueBytes() || !reserve(snapshotBytes)) {
                    throw new IllegalStateException("临时输出交付预算不足");
                }
                bytes = snapshotBytes;
            }
            this.afterSequence = afterSequence;
            this.highWater = highWater;
            this.revision = revision;
            this.sessionId = sessionId;
            this.downstream = downstream;
            this.references = references;
            this.onClosed = onClosed;
            this.worker = Thread.ofVirtual().name("pilot-event-delivery").unstarted(this::drain);
        }

        void start() {
            long started = System.nanoTime();
            timer = monitor.scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                PendingEvent oldest = queue.peek();
                long sending = sendingAt;
                if (sending != 0 && now - sending > TimeUnit.MILLISECONDS.toNanos(limits.sendMillis())) {
                    close("send_timeout");
                } else if (oldest != null
                    && now - oldest.enqueued() > TimeUnit.MILLISECONDS.toNanos(limits.queueWaitMillis())) {
                    close("queue_timeout");
                } else if (replaying && now - started > TimeUnit.MILLISECONDS.toNanos(limits.replayMillis())) {
                    close("replay_timeout");
                }
            }, 50, 50, TimeUnit.MILLISECONDS);
            if (closed.get()) {
                timer.cancel(false);
            }
            worker.start();
        }

        private PendingEvent pending(EventEnvelope event, long size) {
            boolean ephemeral = event.payload().type() == EventType.ASSISTANT_PREVIEW
                || event.payload().type() == EventType.ASSISTANT_REASONING;
            if (!ephemeral && references != null && size > limits.referenceBytes()) {
                return new PendingEvent(null,
                    new EventReference(event.sequence(), event.sessionId(), event.runId(), event.type()), 512,
                    System.nanoTime());
            }
            return new PendingEvent(event, null, size, System.nanoTime());
        }

        void offer(EventEnvelope event, long size) {
            PendingEvent pending = pending(event, size);
            boolean accepted = false;
            synchronized (budget) {
                if (closed.get()) {
                    return;
                }
                if (bytes + pending.bytes() <= limits.queueBytes() && queue.remainingCapacity() > 0
                    && reserve(pending.bytes())) {
                    bytes += pending.bytes();
                    queue.add(pending);
                    accepted = true;
                }
            }
            if (!accepted) {
                close("queue_budget");
            }
        }

        private void release(PendingEvent pending) {
            synchronized (budget) {
                bytes -= pending.bytes();
                queuedBytes.addAndGet(-pending.bytes());
            }
        }

        private boolean checkRevision(boolean force) {
            long now = System.nanoTime();
            if (force || now - revisionCheckedAt >= TimeUnit.MILLISECONDS.toNanos(100)) {
                revisionCheckedAt = now;
                if (repository.historyRevision(sessionId) != revision) {
                    close("history_changed");
                    return false;
                }
            }
            return !closed.get();
        }

        private void send(PendingEvent pending) {
            if (!checkRevision(false)) {
                return;
            }
            sendingAt = System.nanoTime();
            try {
                if (pending.reference() != null) {
                    references.accept(pending.reference());
                } else {
                    downstream.accept(pending.event());
                }
                delivered.incrementAndGet();
            } finally {
                sendingAt = 0;
            }
        }

        private void drain() {
            long began = System.nanoTime();
            try {
                long cursor = afterSequence;
                while (!closed.get() && cursor < highWater) {
                    if (!checkRevision(true)) {
                        return;
                    }
                    replaySlots.acquire();
                    peakReplayPages.accumulateAndGet(replayPages.incrementAndGet(), Math::max);
                    try {
                        List<EventEnvelope> page = repository.replayPage(sessionId, cursor, highWater, limits.pageSize());
                        if (page.isEmpty()) {
                            break;
                        }
                        for (EventEnvelope event : page) {
                            if (closed.get()) {
                                return;
                            }
                            PendingEvent pending = pending(event, EventSize.estimate(event.payload()) + 1024);
                            synchronized (budget) {
                                if (pending.bytes() + bytes > limits.queueBytes() || !reserve(pending.bytes())) {
                                    close("queue_budget");
                                    return;
                                }
                                bytes += pending.bytes();
                            }
                            try {
                                send(pending);
                            } finally {
                                release(pending);
                            }
                            cursor = event.sequence();
                        }
                    } finally {
                        replayPages.decrementAndGet();
                        replaySlots.release();
                    }
                }
                replaying = false;
                replayNanos.addAndGet(System.nanoTime() - began);
                if (!checkRevision(true)) {
                    return;
                }
                if (snapshots != null && !closed.get()) {
                    sendingAt = System.nanoTime();
                    try {
                        snapshots.accept(snapshot);
                    } finally {
                        releaseSnapshot();
                        sendingAt = 0;
                    }
                }
                while (checkRevision(false)) {
                    PendingEvent pending = queue.poll(1, TimeUnit.SECONDS);
                    if (pending != null) {
                        try {
                            send(pending);
                        } finally {
                            release(pending);
                        }
                    }
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                log.debug("订阅交付失败：{}", failure.getClass().getSimpleName());
                close(failure instanceof EventTooLargeException ? "event_too_large" : "delivery_error");
            } finally {
                releaseSnapshot();
                close();
                workers.decrementAndGet();
            }
        }

        private void releaseSnapshot() {
            if (snapshotBytes != 0) {
                synchronized (budget) {
                    bytes -= snapshotBytes;
                    queuedBytes.addAndGet(-snapshotBytes);
                    snapshotBytes = 0;
                }
            }
            snapshot = null;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public String closeReason() {
            return reason;
        }

        @Override
        public void close() {
            close("closed");
        }

        private void close(String why) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            reason = why;
            unsubscribe(sessionId, this);
            synchronized (budget) {
                PendingEvent pending;
                while ((pending = queue.poll()) != null) {
                    release(pending);
                }
            }
            var scheduled = timer;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            worker.interrupt();
            closedReasons.computeIfAbsent(why, ignored -> new AtomicLong()).incrementAndGet();
            if (onClosed != null) {
                Thread.ofVirtual().name("pilot-event-close").start(() -> {
                    try {
                        onClosed.run();
                    } catch (RuntimeException failure) {
                        log.debug("订阅关闭回调失败", failure);
                    }
                });
            }
        }
    }
}
