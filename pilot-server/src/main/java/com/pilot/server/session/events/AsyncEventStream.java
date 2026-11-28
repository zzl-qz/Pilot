package com.pilot.server.session.events;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

/** Servlet 非阻塞 SSE：只在容器允许写时发送，等待中的字节和最长等待均有界。 */
@Slf4j
public final class AsyncEventStream implements AutoCloseable {
    /** 这么久没有事件就发一次心跳，防止代理把空闲连接掐掉。 */
    private static final long HEARTBEAT_INTERVAL_NANOS = Duration.ofSeconds(20).toNanos();
    private final SseStreamTransport transport;
    private final AsyncContext context;
    private final ServletOutputStream output;
    private final long byteLimit;
    private final long timeoutNanos;

    private final Runnable onClosed;
    private final Runnable heartbeat;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean heartbeatRunning = new AtomicBoolean();

    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile ScheduledFuture<?> monitor;
    private long pendingBytes;
    private long heartbeatAt = System.nanoTime();
    private boolean ending;

    private static final class Frame {
        final byte[] data;
        final long enqueued = System.nanoTime();
        int offset;

        Frame(byte[] data) {
            this.data = data;
        }
    }

    public AsyncEventStream(SseStreamTransport transport, HttpServletRequest request, HttpServletResponse response,
        long byteLimit, Duration timeout, Runnable onClosed, Runnable heartbeat) throws IOException {
        this.transport = transport;
        this.byteLimit = byteLimit;
        this.timeoutNanos = timeout.toNanos();
        this.onClosed = onClosed;
        this.heartbeat = heartbeat;
        response.setStatus(200);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        context = request.startAsync();
        context.setTimeout(0);
        output = response.getOutputStream();
        transport.opened();
        context.addListener(new AsyncListener() {
            public void onComplete(AsyncEvent event) {
                terminate("complete");
            }

            public void onTimeout(AsyncEvent event) {
                terminate("servlet_timeout");
            }

            public void onError(AsyncEvent event) {
                terminate("client_error");
            }

            public void onStartAsync(AsyncEvent event) {
            }
        });
        try {
            output.setWriteListener(new WriteListener() {
                public void onWritePossible() {
                    drain();
                }

                public void onError(Throwable failure) {
                    terminate("write_error");
                }
            });
            monitor = transport.timer().scheduleAtFixedRate(this::check, 100, 100, TimeUnit.MILLISECONDS);
            if (closed.get()) {
                monitor.cancel(false);
            }
        } catch (RuntimeException failure) {
            terminate("initialization_error");
            throw failure;
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean send(String type, String id, String json) {
        if (!offer(((id == null ? "" : "id:" + id + "\n") + "event:" + type + "\ndata:" + json + "\n\n")
            .getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        // 仅暂停该连接的投递 worker，让历史分页跟随网络进度；不持有会话发布锁。
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (lock) {
            while (!closed.get() && (pendingBytes > byteLimit / 2 || queue.size() > 1024)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!closed.get() && pendingBytes <= byteLimit / 2 && queue.size() <= 1024) {
                return true;
            }
        }
        terminate("send_timeout");
        return false;
    }

    private boolean offer(byte[] data) {
        boolean overflow;
        synchronized (lock) {
            if (closed.get() || ending) {
                return false;
            }
            overflow =
                pendingBytes + data.length > byteLimit || queue.size() >= 4096 || !transport.reserve(data.length);
            if (!overflow) {
                queue.add(new Frame(data));
                pendingBytes += data.length;
            }
        }
        if (overflow) {
            terminate("byte_or_count_limit");
            return false;
        }
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
                    frame.offset += count;
                    transport.sent(count);
                    if (frame.offset == frame.data.length) {
                        pendingBytes -= frame.data.length;
                        transport.release(frame.data.length);
                        queue.remove();
                        lock.notifyAll();
                        transport.frameSent(System.nanoTime() - frame.enqueued);
                    }
                }
                // 非阻塞 flush 仅在可写时执行，不能退回阻塞式 send。
                if (!closed.get() && output.isReady()) {
                    output.flush();
                }
                complete = ending && queue.isEmpty();
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("事件流写入失败", failure);
            terminate("write_error");
        }
        if (complete) {
            terminate("complete");
        }
    }

    private void check() {
        long now = System.nanoTime();
        boolean expired;
        synchronized (lock) {
            expired = !queue.isEmpty() && now - queue.peek().enqueued >= timeoutNanos;
        }
        if (expired) {
            terminate("send_timeout");
            return;
        }
        if (!closed.get() && now - heartbeatAt >= HEARTBEAT_INTERVAL_NANOS
            && heartbeatRunning.compareAndSet(false, true)) {
            heartbeatAt = now;
            Thread.ofVirtual().name("pilot-stream-heartbeat").start(() -> {
                try {
                    heartbeat.run();
                    offer(":ping\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException failure) {
                    terminate("heartbeat_error");
                } finally {
                    heartbeatRunning.set(false);
                }
            });
        }
    }

    /** 允许已排队的控制帧写完；跟不上的连接仍受期限约束。 */
    public void finish() {
        synchronized (lock) {
            ending = true;
        }
        drain();
    }

    @Override
    public void close() {
        terminate("closed");
    }

    public void terminate(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> timer = monitor;
        if (timer != null) {
            timer.cancel(false);
        }
        synchronized (lock) {
            transport.release(pendingBytes);
            pendingBytes = 0;
            queue.clear();
            lock.notifyAll();
        }
        transport.closed(reason);
        try {
            context.complete();
        } catch (RuntimeException ignored) {
        }
        Thread.ofVirtual().name("pilot-stream-close").start(onClosed);
    }
}
