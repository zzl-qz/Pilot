package com.pilot.server.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Servlet 非阻塞 SSE：只在容器允许写时发送，等待中的字节和最长等待均有界。 */
public final class AsyncEventStream implements AutoCloseable {
    private static final java.util.concurrent.ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "pilot-stream-monitor"); thread.setDaemon(true); return thread;
    });
    private static final AtomicLong ACTIVE = new AtomicLong(), BYTES = new AtomicLong(), PEAK = new AtomicLong();
    private static final AtomicLong SENT = new AtomicLong(), FRAMES = new AtomicLong(), MAX_SEND_NANOS = new AtomicLong();
    private static final Map<String, AtomicLong> CLOSED = new ConcurrentHashMap<>();
    private final AsyncContext context;
    private final ServletOutputStream output;
    private final long byteLimit, timeoutNanos;
    private final Runnable onClosed, heartbeat;
    private final AtomicBoolean closed = new AtomicBoolean(), heartbeatRunning = new AtomicBoolean();
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile ScheduledFuture<?> monitor;
    private long pendingBytes;
    private long heartbeatAt = System.nanoTime();
    private boolean ending;
    private static final class Frame {
        final byte[] data; final long enqueued = System.nanoTime(); int offset;
        Frame(byte[] data) { this.data = data; }
    }
    public record Metrics(long connections, long queuedBytes, long peakQueuedBytes, long sentBytes,
        long sentFrames, long maxSendNanos, Map<String, Long> closed) {}
    public static Metrics metrics() {
        Map<String, Long> reasons = new java.util.TreeMap<>(); CLOSED.forEach((key, value) -> reasons.put(key, value.get()));
        return new Metrics(ACTIVE.get(), BYTES.get(), PEAK.get(), SENT.get(), FRAMES.get(), MAX_SEND_NANOS.get(), Map.copyOf(reasons));
    }

    public AsyncEventStream(HttpServletRequest request, HttpServletResponse response, long byteLimit,
        Duration timeout, Runnable onClosed, Runnable heartbeat) throws IOException {
        this.byteLimit = byteLimit; this.timeoutNanos = timeout.toNanos(); this.onClosed = onClosed; this.heartbeat = heartbeat;
        response.setStatus(200); response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache"); response.setHeader("X-Accel-Buffering", "no");
        context = request.startAsync(); context.setTimeout(0);
        output = response.getOutputStream();
        ACTIVE.incrementAndGet();
        context.addListener(new AsyncListener() {
            public void onComplete(AsyncEvent event) { terminate("complete"); }
            public void onTimeout(AsyncEvent event) { terminate("servlet_timeout"); }
            public void onError(AsyncEvent event) { terminate("client_error"); }
            public void onStartAsync(AsyncEvent event) {}
        });
        try {
            output.setWriteListener(new WriteListener() {
                public void onWritePossible() { drain(); }
                public void onError(Throwable failure) { terminate("write_error"); }
            });
            monitor = TIMER.scheduleAtFixedRate(this::check, 100, 100, TimeUnit.MILLISECONDS);
            if (closed.get()) monitor.cancel(false);
        } catch (RuntimeException failure) { terminate("initialization_error"); throw failure; }
    }

    public boolean isClosed() { return closed.get(); }
    public boolean send(String type, String id, String json) {
        return offer(((id == null ? "" : "id:" + id + "\n") + "event:" + type + "\ndata:" + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }
    private boolean offer(byte[] data) {
        boolean overflow;
        synchronized (lock) {
            if (closed.get() || ending) return false;
            overflow = pendingBytes + data.length > byteLimit || queue.size() >= 4096 || !reserve(data.length);
            if (!overflow) {
                queue.add(new Frame(data)); pendingBytes += data.length;
                PEAK.accumulateAndGet(BYTES.get(), Math::max);
            }
        }
        if (overflow) { terminate("byte_or_count_limit"); return false; }
        drain();
        return !closed.get();
    }
    private void drain() {
        boolean complete = false;
        try {
            synchronized (lock) {
                while (!closed.get() && !queue.isEmpty() && output.isReady()) {
                    Frame frame = queue.peek();
                    int count = Math.min(8192, frame.data.length - frame.offset);
                    output.write(frame.data, frame.offset, count);
                    frame.offset += count; SENT.addAndGet(count);
                    if (frame.offset == frame.data.length) {
                        pendingBytes -= frame.data.length; BYTES.addAndGet(-frame.data.length);
                        queue.remove(); FRAMES.incrementAndGet();
                        MAX_SEND_NANOS.accumulateAndGet(System.nanoTime() - frame.enqueued, Math::max);
                    }
                }
                // 非阻塞 flush 仅在可写时执行，不能退回阻塞式 send。
                if (!closed.get() && output.isReady()) output.flush();
                complete = ending && queue.isEmpty();
            }
        } catch (IOException | RuntimeException failure) { terminate("write_error"); }
        if (complete) terminate("complete");
    }
    private static boolean reserve(long bytes) {
        long current;
        do { current = BYTES.get(); if (bytes > 128L * 1024 * 1024 - current) return false; }
        while (!BYTES.compareAndSet(current, current + bytes));
        return true;
    }
    private void check() {
        long now = System.nanoTime();
        boolean expired;
        synchronized (lock) { expired = !queue.isEmpty() && now - queue.peek().enqueued >= timeoutNanos; }
        if (expired) { terminate("send_timeout"); return; }
        if (!closed.get() && now - heartbeatAt >= Duration.ofSeconds(20).toNanos() && heartbeatRunning.compareAndSet(false, true)) {
            heartbeatAt = now;
            Thread.ofVirtual().name("pilot-stream-heartbeat").start(() -> {
                try { heartbeat.run(); offer(":ping\n\n".getBytes(StandardCharsets.UTF_8)); }
                catch (RuntimeException failure) { terminate("heartbeat_error"); }
                finally { heartbeatRunning.set(false); }
            });
        }
    }
    /** 允许已排队的控制帧写完；跟不上的连接仍受期限约束。 */
    public void finish() { synchronized (lock) { ending = true; } drain(); }
    @Override public void close() { terminate("closed"); }
    public void terminate(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledFuture<?> timer = monitor; if (timer != null) timer.cancel(false);
        synchronized (lock) { BYTES.addAndGet(-pendingBytes); pendingBytes = 0; queue.clear(); }
        ACTIVE.decrementAndGet(); CLOSED.computeIfAbsent(reason, ignored -> new AtomicLong()).incrementAndGet();
        try { context.complete(); } catch (RuntimeException ignored) {}
        Thread.ofVirtual().name("pilot-stream-close").start(onClosed);
    }
}
