package com.pilot.server.session.events;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

/** SSE 连接共享的调度资源与聚合指标。 */
@Component
public final class SessionEventTransport {
    private static final long MAX_QUEUED_BYTES = 128L * 1024 * 1024;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "pilot-stream-monitor");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong peakQueuedBytes = new AtomicLong();
    private final AtomicLong sentBytes = new AtomicLong();
    private final AtomicLong sentFrames = new AtomicLong();
    private final AtomicLong maxSendNanos = new AtomicLong();
    private final Map<String, AtomicLong> closed = new ConcurrentHashMap<>();

    public record Metrics(long connections, long queuedBytes, long peakQueuedBytes, long sentBytes, long sentFrames,
        long maxSendNanos, Map<String, Long> closed) {
    }

    public ScheduledExecutorService timer() {
        return timer;
    }

    public void opened() {
        active.incrementAndGet();
    }

    public boolean reserve(long bytes) {
        long current;
        do {
            current = queuedBytes.get();
            if (bytes > MAX_QUEUED_BYTES - current) {
                return false;
            }
        } while (!queuedBytes.compareAndSet(current, current + bytes));
        peakQueuedBytes.accumulateAndGet(current + bytes, Math::max);
        return true;
    }

    public void release(long bytes) {
        queuedBytes.addAndGet(-bytes);
    }

    public void sent(long bytes) {
        sentBytes.addAndGet(bytes);
    }

    public void frameSent(long elapsedNanos) {
        sentFrames.incrementAndGet();
        maxSendNanos.accumulateAndGet(elapsedNanos, Math::max);
    }

    public void closed(String reason) {
        active.decrementAndGet();
        closed.computeIfAbsent(reason, ignored -> new AtomicLong()).incrementAndGet();
    }

    public Metrics metrics() {
        Map<String, Long> reasons = new TreeMap<>();
        closed.forEach((key, value) -> reasons.put(key, value.get()));
        return new Metrics(active.get(), queuedBytes.get(), peakQueuedBytes.get(), sentBytes.get(), sentFrames.get(),
            maxSendNanos.get(), Map.copyOf(reasons));
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }
}
