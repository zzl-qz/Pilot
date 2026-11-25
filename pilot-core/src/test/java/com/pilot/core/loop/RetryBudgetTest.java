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

    @Test
    void appliesTieredBackoffForRateLimitServerErrorAndNetworkAndRespectsRetryAfter() {
        AtomicLong clock = new AtomicLong();
        RetryBudget rateLimit = budget(new RetryBudget.Settings(10, 600_000, 1_000, 30_000, 0), clock, 1.0);
        RetryBudget serverError = budget(new RetryBudget.Settings(10, 600_000, 1_000, 30_000, 0), clock, 1.0);
        RetryBudget network = budget(new RetryBudget.Settings(10, 600_000, 1_000, 30_000, 0), clock, 1.0);

        long rate1 = rateLimit.afterFailure("HTTP 429", RetryBudget.Tier.RATE_LIMIT, -1L).delayMs();
        long server1 = serverError.afterFailure("HTTP 503", RetryBudget.Tier.SERVER_ERROR, -1L).delayMs();
        long net1 = network.afterFailure("EOF", RetryBudget.Tier.NETWORK, -1L).delayMs();
        assertEquals(2_000L, rate1, "429 限流首轮退避应为 2s");
        assertEquals(1_000L, server1, "5xx 首轮退避应从 1s 起步");
        assertEquals(1_000L, net1, "网络断连首轮重连应保持 1s 快速起步");
        // 高次退避封顶三档拉开差距：429=60s > 5xx=45s > NETWORK=30s
        assertEquals(60_000L, rateLimit.delayFor(8, RetryBudget.Tier.RATE_LIMIT));
        assertEquals(45_000L, serverError.delayFor(8, RetryBudget.Tier.SERVER_ERROR));
        assertEquals(30_000L, network.delayFor(8, RetryBudget.Tier.NETWORK));

        // Retry-After 提示优先作为退避下限，且受上限与剩余总时长约束
        RetryBudget.Decision hinted = rateLimit.afterFailure("HTTP 429", RetryBudget.Tier.RATE_LIMIT, 12_000L);
        assertTrue(hinted.retry());
        assertEquals(12_000L, hinted.delayMs());

        RetryBudget.Decision hugeHint = rateLimit.afterFailure("HTTP 429", RetryBudget.Tier.RATE_LIMIT, 999_000L);
        assertEquals(120_000L, hugeHint.delayMs(), "429 超大 Retry-After 应封顶到 120s");

        RetryBudget serverHint = budget(new RetryBudget.Settings(10, 600_000, 1_000, 30_000, 0), clock, 1.0);
        assertEquals(15_000L, serverHint.afterFailure("HTTP 504", RetryBudget.Tier.SERVER_ERROR, 120_000L).delayMs(),
            "5xx 网关超时首次重试不应因 Retry-After 干等 120s");
        assertEquals(30_000L, serverHint.afterFailure("HTTP 504", RetryBudget.Tier.SERVER_ERROR, 120_000L).delayMs(),
            "5xx 后续重试 Retry-After 封顶 30s");
    }

    @Test
    void compensateSleepPreventsOsSleepFromExhaustingBudgetOrTrippingBreaker() {
        AtomicLong clock = new AtomicLong(1_000L);
        RetryBudget budget = budget(new RetryBudget.Settings(10, 60_000, 1_000, 30_000, 3), clock, 1.0);

        assertTrue(budget.afterFailure("EOF").retry());
        assertTrue(budget.afterFailure("EOF").retry());
        // 模拟笔记本休眠 10 分钟（远超 60s 预算），唤醒后补偿休眠时长并清零连续失败计数
        clock.addAndGet(600_000L);
        budget.compensateSleep(600_000L);

        RetryBudget.Decision afterWake = budget.afterFailure("EOF", RetryBudget.Tier.NETWORK, 2_000L);
        assertTrue(afterWake.retry(), "休眠唤醒补偿后不应耗尽重试预算或立刻熔断: " + afterWake);
        assertEquals(2_000L, afterWake.delayMs());
        assertTrue(afterWake.remainingMs() >= 59_000L, "休眠时长不应计入已消耗预算: " + afterWake);
    }
}
