package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RetryBudgetTest {

    private static RetryBudget budget(RetryBudget.Settings settings, AtomicLong clock, double jitter) {
        return new RetryBudget(settings, clock::get, () -> jitter);
    }

    @Test
    void backsOffExponentiallyUpToTheCap() {
        AtomicLong clock = new AtomicLong();
        RetryBudget budget = budget(new RetryBudget.Settings(10, 600_000, 1_000, 30_000, 0), clock, 1.0);

        assertEquals(1_000L, budget.afterFailure("HTTP 500").delayMs());
        assertEquals(2_000L, budget.afterFailure("HTTP 500").delayMs());
        assertEquals(4_000L, budget.afterFailure("HTTP 500").delayMs());
        assertEquals(8_000L, budget.afterFailure("HTTP 500").delayMs());
        assertEquals(16_000L, budget.afterFailure("HTTP 500").delayMs());
        assertEquals(30_000L, budget.afterFailure("HTTP 500").delayMs());
        assertEquals(30_000L, budget.afterFailure("HTTP 500").delayMs());
    }

    @Test
    void jitterOnlyShortensTheWait() {
        AtomicLong clock = new AtomicLong();
        RetryBudget budget = budget(new RetryBudget.Settings(10, 600_000, 1_000, 30_000, 0), clock, 0.0);
        // 等量抖动：下限是一半，不会出现「越抖等待越久」
        assertEquals(500L, budget.afterFailure("boom").delayMs());
        assertEquals(1_000L, budget.afterFailure("boom").delayMs());
        assertEquals(2_000L, budget.afterFailure("boom").delayMs());
    }

    @Test
    void stopsAfterTheAttemptBudget() {
        AtomicLong clock = new AtomicLong();
        RetryBudget budget = budget(new RetryBudget.Settings(2, 600_000, 1_000, 30_000, 0), clock, 1.0);

        assertTrue(budget.afterFailure("HTTP 503").retry());
        assertTrue(budget.afterFailure("HTTP 503").retry());
        RetryBudget.Decision exhausted = budget.afterFailure("HTTP 503");
        assertFalse(exhausted.retry());
        assertFalse(exhausted.circuitOpen());
        assertTrue(exhausted.reason().contains("上限"), exhausted.reason());
    }

    @Test
    void stopsWhenTheElapsedBudgetIsGone() {
        AtomicLong clock = new AtomicLong();
        RetryBudget budget = budget(new RetryBudget.Settings(100, 5_000, 1_000, 30_000, 0), clock, 1.0);

        assertTrue(budget.afterFailure("HTTP 503").retry());
        clock.addAndGet(3_000);
        RetryBudget.Decision second = budget.afterFailure("HTTP 503");
        assertTrue(second.retry());
        // 剩余预算不足以覆盖整个退避时必须缩短等待，不能等超了再停
        assertTrue(second.delayMs() <= second.remainingMs(), second.toString());

        clock.addAndGet(3_000);
        RetryBudget.Decision exhausted = budget.afterFailure("HTTP 503");
        assertFalse(exhausted.retry());
        assertEquals(0L, exhausted.remainingMs());
    }

    @Test
    void opensTheCircuitAfterConsecutiveFailures() {
        AtomicLong clock = new AtomicLong();
        RetryBudget budget = budget(new RetryBudget.Settings(100, 600_000, 1_000, 30_000, 3), clock, 1.0);

        assertTrue(budget.afterFailure("HTTP 500").retry());
        assertTrue(budget.afterFailure("HTTP 500").retry());
        RetryBudget.Decision open = budget.afterFailure("HTTP 500");
        assertFalse(open.retry());
        assertTrue(open.circuitOpen(), open.toString());
        assertTrue(open.reason().contains("熔断"), open.reason());
    }

    @Test
    void successResetsTheWholeBudget() {
        AtomicLong clock = new AtomicLong();
        RetryBudget budget = budget(new RetryBudget.Settings(2, 600_000, 1_000, 30_000, 2), clock, 1.0);

        budget.afterFailure("HTTP 500");
        budget.afterFailure("HTTP 500");
        budget.reset();

        RetryBudget.Decision afterReset = budget.afterFailure("HTTP 500");
        assertTrue(afterReset.retry());
        assertEquals(1, afterReset.attempt());
        assertEquals(1_000L, afterReset.delayMs());
    }
}
