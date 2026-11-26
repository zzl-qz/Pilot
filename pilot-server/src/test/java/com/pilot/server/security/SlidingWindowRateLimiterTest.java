package com.pilot.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

    @Test
    void allowsUpToTheLimitThenRefuses() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, Duration.ofMinutes(1), clock::get);

        assertTrue(limiter.allow("10.0.0.1"));
        assertTrue(limiter.allow("10.0.0.1"));
        assertFalse(limiter.allow("10.0.0.1"));
        assertEquals(0, limiter.remaining("10.0.0.1"));
        // 不同来源互不影响
        assertTrue(limiter.allow("10.0.0.2"));
    }

    @Test
    void windowSlidesInsteadOfResetting() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, Duration.ofSeconds(10), clock::get);

        assertTrue(limiter.allow("a"));
        clock.addAndGet(5_000);
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));

        // 第一个请求滑出窗口后腾出一个名额，而不是整窗清零
        clock.addAndGet(5_100);
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
    }

    @Test
    void pruneRemovesIdleSources() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, Duration.ofSeconds(5), clock::get);

        assertTrue(limiter.allow("idle"));
        clock.addAndGet(6_000);
        limiter.prune();

        assertTrue(limiter.allow("idle"));
    }

    @Test
    void allowAutomaticallyPrunesExpiredSources() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, Duration.ofSeconds(5), clock::get);
        assertTrue(limiter.allow("idle"));
        clock.addAndGet(6_000);
        assertTrue(limiter.allow("active"));
        assertTrue(limiter.allow("idle"));
    }

    @Test
    void sourceLimitRejectsNewSourcesWithoutDroppingActiveLimits() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, Duration.ofMinutes(1));
        for (int i = 0; i < 2048; i++) {
            assertTrue(limiter.allow("source-" + i));
        }
        assertFalse(limiter.allow("source-overflow"));
        assertFalse(limiter.allow("source-0"));
    }
}
