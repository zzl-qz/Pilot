package com.pilot.server.security;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按来源限流的滑动窗口。
 *
 * <p>选择滑动窗口而不是固定窗口：固定窗口在边界处会放过两倍流量。
 * 只在单进程内生效，多实例部署时需要换成共享存储。
 */
public final class SlidingWindowRateLimiter {
    /** 没指定窗口时按每分钟限流。 */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_SOURCES = 2048;

    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private long lastPrune;
    private int callsSincePrune;

    public SlidingWindowRateLimiter(int limitPerWindow, Duration window) {
        this(limitPerWindow, window == null ? DEFAULT_WINDOW : window, System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(int limitPerWindow, Duration window, LongSupplier clock) {
        this.limit = Math.max(1, limitPerWindow);
        this.windowMillis = Math.max(1L, (window == null ? DEFAULT_WINDOW : window).toMillis());
        this.clock = clock;
    }

    /** @return true 表示放行；false 表示超过窗口上限 */
    public boolean allow(String key) {
        long now = clock.getAsLong();
        synchronized (this) {
            callsSincePrune++;
            if (now - lastPrune >= windowMillis || callsSincePrune >= 1024) {
                pruneExpired(now);
                lastPrune = now;
                callsSincePrune = 0;
            }
        }
        String source = key == null ? "unknown" : key;
        Deque<Long> window;
        synchronized (this) {
            window = hits.get(source);
            if (window == null) {
                if (hits.size() >= MAX_SOURCES) {
                    return false;
                }
                window = new ArrayDeque<>();
                hits.put(source, window);
            }
        }
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** 剩余可用请求数，用于把限流状态告诉调用方。 */
    public int remaining(String key) {
        long now = clock.getAsLong();
        Deque<Long> window = hits.get(key == null ? "unknown" : key);
        if (window == null) {
            return limit;
        }
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
                window.pollFirst();
            }
            return Math.max(0, limit - window.size());
        }
    }

    /** 定期清理不再活跃的来源，避免内存只增不减。 */
    public void prune() {
        pruneExpired(clock.getAsLong());
    }

    private void pruneExpired(long now) {
        hits.entrySet().removeIf(entry -> {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                return window.isEmpty() || now - window.peekLast() >= windowMillis;
            }
        });
    }
}
