package com.pilot.core.http;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 请求头和响应体共用一个截止时间；关闭、取消和超时都会释放底层连接。 */
public final class BoundedHttp {
    public static final int ERROR_BYTES = 64 * 1024;
    public static final int JSON_BYTES = 32 * 1024 * 1024;
    public static final int STREAM_BYTES = 64 * 1024 * 1024;
    public static final int SSE_LINE_CHARS = 1024 * 1024;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private BoundedHttp() {
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        var executor =
            new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("http-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public static Exchange open(HttpClient client, HttpRequest request, Duration idleTimeout, int maxBytes)
        throws IOException, InterruptedException {
        Duration timeout = request.timeout().orElseThrow(() -> new IllegalArgumentException("请求必须设置总超时"));
        if (idleTimeout.isNegative() || idleTimeout.isZero() || maxBytes <= 0) {
            throw new IllegalArgumentException("空闲超时和响应大小上限必须为正数");
        }
        var exchange = new Exchange(idleTimeout.toNanos(), maxBytes);
        try {
            exchange.start(client, request, timeout);
            exchange.awaitHeaders();
            return exchange;
        } catch (IOException | InterruptedException | RuntimeException e) {
            exchange.close();
            throw e;
        }
    }

    public static TextResponse text(HttpClient client, HttpRequest request, Duration idleTimeout, int maxBytes)
        throws IOException, InterruptedException {
        try (Exchange response = open(client, request, idleTimeout, maxBytes)) {
            byte[] bytes = response.statusCode() >= 200 && response.statusCode() < 300 ? response.body().readAllBytes()
                : response.body().readNBytes(ERROR_BYTES);
            return new TextResponse(response.statusCode(), response.headers(),
                new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /** 与 readLine 相同的换行规则，但在分配完整超长行之前拒绝。 */
    public static String readLine(BufferedReader reader, int maxChars) throws IOException {
        var result = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') {
                return result.toString();
            }
            if (value == '\r') {
                reader.mark(1);
                if (reader.read() != '\n') {
                    reader.reset();
                }
                return result.toString();
            }
            if (result.length() >= maxChars) {
                throw new ResponseLimitException("SSE 行超过 " + maxChars + " 字符上限");
            }
            result.append((char)value);
        }
        return result.isEmpty() ? null : result.toString();
    }

    public record TextResponse(int statusCode, HttpHeaders headers, String body) {
    }

    public static final class ResponseLimitException extends IOException {
        public ResponseLimitException(String message) {
            super(message);
        }
    }

    public static final class Exchange implements AutoCloseable {
        private final long idleNanos;
        private final int maxBytes;
        private long lastProgress = System.nanoTime();
        private boolean closed;
        private IOException failure;
        private CompletableFuture<HttpResponse<InputStream>> pending;
        private HttpResponse<InputStream> response;
        private InputStream body;
        private ScheduledFuture<?> totalTimer;
        private ScheduledFuture<?> idleTimer;

        private Exchange(long idleNanos, int maxBytes) {
            this.idleNanos = idleNanos;
            this.maxBytes = maxBytes;
        }

        private synchronized void start(HttpClient client, HttpRequest request, Duration total) {
            totalTimer = DEADLINES.schedule(() -> expire("HTTP 请求总超时（包含响应体）"), total.toNanos(), TimeUnit.NANOSECONDS);
            idleTimer = DEADLINES.schedule(this::checkIdle, idleNanos, TimeUnit.NANOSECONDS);
            pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            pending.thenAccept(value -> {
                boolean discard;
                synchronized (this) {
                    discard = closed;
                    if (!discard) {
                        response = value;
                        lastProgress = System.nanoTime();
                    }
                }
                if (discard) {
                    closeBody(value.body());
                }
            });
        }

        private void awaitHeaders() throws IOException, InterruptedException {
            HttpResponse<InputStream> value;
            try {
                value = pending.get();
            } catch (ExecutionException e) {
                // JDK 取消可能直接抛 CancellationException，也可能包在 ExecutionException 中。
                // 自己触发的超时优先，不能因线程竞态被改写成普通网络错误。
                checkFailure();
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw new IOException("HTTP 请求失败", e.getCause());
            } catch (CancellationException e) {
                synchronized (this) {
                    if (failure != null) {
                        throw failure;
                    }
                }
                throw new IOException("HTTP 请求已关闭", e);
            }
            synchronized (this) {
                checkFailure();
                response = value;
                lastProgress = System.nanoTime();
                int limit = value.statusCode() >= 200 && value.statusCode() < 300 ? maxBytes : ERROR_BYTES;
                body = new FilterInputStream(value.body()) {
                    private long received;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                        if (length == 0) {
                            return 0;
                        }
                        checkFailure();
                        int count;
                        try {
                            count = in.read(bytes, offset, (int)Math.min(length, limit - received + 1));
                        } catch (IOException e) {
                            checkFailure();
                            throw e;
                        }
                        checkFailure();
                        if (count > 0) {
                            received += count;
                            progress();
                            if (received > limit) {
                                throw new ResponseLimitException("HTTP 响应超过 " + limit + " 字节上限");
                            }
                        }
                        return count;
                    }
                };
            }
        }

        private synchronized void checkFailure() throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (closed) {
                throw new IOException("HTTP 请求已关闭");
            }
        }

        private synchronized void progress() {
            lastProgress = System.nanoTime();
        }

        private void checkIdle() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                long remaining = idleNanos - (System.nanoTime() - lastProgress);
                if (remaining > 0) {
                    idleTimer = DEADLINES.schedule(this::checkIdle, remaining, TimeUnit.NANOSECONDS);
                    return;
                }
            }
            expire("HTTP 响应空闲超时");
        }

        private void expire(String message) {
            synchronized (this) {
                if (closed || failure != null) {
                    return;
                }
                failure = new HttpTimeoutException(message);
            }
            // close() 可能触发底层连接清理，不能占住所有请求共用的定时线程。
            Thread.ofVirtual().name("http-timeout-close").start(this::close);
        }

        public int statusCode() {
            return response.statusCode();
        }

        public HttpHeaders headers() {
            return response.headers();
        }

        public InputStream body() {
            return body;
        }

        @Override
        public void close() {
            HttpResponse<InputStream> current;
            CompletableFuture<HttpResponse<InputStream>> request;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                if (totalTimer != null) {
                    totalTimer.cancel(false);
                }
                if (idleTimer != null) {
                    idleTimer.cancel(false);
                }
                current = response;
                request = pending;
            }
            if (request != null) {
                request.cancel(true);
            }
            if (current != null) {
                closeBody(current.body());
            }
        }

        private static void closeBody(InputStream stream) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 原来的超时、取消或读取异常比清理异常更有用。
            }
        }
    }
}
