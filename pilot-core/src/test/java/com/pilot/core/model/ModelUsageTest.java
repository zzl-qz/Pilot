package com.pilot.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用量归一化与缓存口径。这两件事错了不会有异常，只会让成本面板显示一个看着合理、
 * 实际反向的数字，所以每条断言都写死具体数值。
 */
class ModelUsageTest {

    @Test
    void keepsCachedTokensFromOpenAiStyleUsage() {
        // OpenAI 风格：prompt_tokens 已包含 cached_tokens，缓存是分子不是加数
        ModelUsage usage = new ModelUsage(1_000, 40, 0, 800, 0, true);

        assertEquals(1_000, usage.inputTokens(), "总输入不能把缓存再加一遍");
        assertEquals(800, usage.cachedInputTokens());
        assertEquals(0.8, usage.cacheHitRatio(), 1e-9);
        assertEquals(200, usage.fullPriceInputTokens());
    }

    @Test
    void addsCachedTokensBackForAnthropicStyleUsage() {
        // Anthropic 风格：input_tokens 不含缓存，解析时已把缓存量加进总输入
        ModelUsage usage = new ModelUsage(1_000, 40, 0, 700, 200, true);

        assertEquals(1_000, usage.inputTokens());
        assertEquals(700, usage.cachedInputTokens());
        assertEquals(200, usage.cacheWriteTokens());
        assertEquals(0.7, usage.cacheHitRatio(), 1e-9);
        assertEquals(100, usage.fullPriceInputTokens(), "扣掉命中与写入后才是全价部分");
    }

    @Test
    void treatsMissingCacheObservationAsZeroHit() {
        assertEquals(0, new ModelUsage(1_000, 40, 0, true).cachedInputTokens(),
                "四参构造器用于没有缓存观测的路径");
        assertEquals(0, new ModelUsage(1_000, 40, 0, true).cacheWriteTokens());
        assertEquals(0d, new ModelUsage(1_000, 40, 0, true).cacheHitRatio());
    }

    @Test
    void neverReportsHitRatioForEstimatedUsage() {
        // 本地估算没有缓存观测：显示 0 命中会误导，但更不能凭空造一个命中率
        ModelUsage estimated = new ModelUsage(1_000, 40, 0, 900, 0, false);

        assertFalse(estimated.exact());
        assertEquals(0d, estimated.cacheHitRatio(), "估算路径的命中率必须是 0，不能拿缓存字段算");
    }

    @Test
    void clampsCacheLargerThanReportedInput() {
        // 端点字段偶尔自相矛盾：缓存量大于总输入。命中率不能因此超过 100%
        ModelUsage usage = new ModelUsage(100, 10, 0, 400, 300, true);

        assertEquals(100, usage.cachedInputTokens() + usage.cacheWriteTokens(),
                "缓存之和必须钳到总输入内");
        assertEquals(100, usage.cachedInputTokens(), "先保命中，余量才算写入");
        assertEquals(0, usage.cacheWriteTokens());
        assertTrue(usage.cacheHitRatio() <= 1.0);
        assertEquals(0, usage.fullPriceInputTokens());
    }

    @Test
    void keepsHitBeforeWriteWhenBothExceedInput() {
        ModelUsage usage = new ModelUsage(300, 10, 0, 200, 200, true);

        assertEquals(200, usage.cachedInputTokens(), "命中优先保留");
        assertEquals(100, usage.cacheWriteTokens(), "余下的额度才归写入");
    }

    @Test
    void normalizesNegativeValues() {
        ModelUsage usage = new ModelUsage(-5, -1, -2, -3, -4, true);

        assertEquals(0, usage.inputTokens());
        assertEquals(0, usage.outputTokens());
        assertEquals(0, usage.reasoningTokens());
        assertEquals(0, usage.cachedInputTokens());
        assertEquals(0, usage.cacheWriteTokens());
    }

    @Test
    void unknownUsageIsEmptyAndInexact() {
        ModelUsage usage = ModelUsage.unknown();

        assertEquals(0, usage.inputTokens());
        assertEquals(0, usage.cachedInputTokens());
        assertFalse(usage.exact());
        assertFalse(usage.present());
    }

    @Test
    void presentIgnoresCacheOnlyFields() {
        // 缓存是输入的子集：只有缓存、没有实际用量时不算「端点报了 usage」
        assertFalse(new ModelUsage(0, 0, 0, 0, 0, true).present());
        assertTrue(new ModelUsage(10, 0, 0, 10, 0, true).present());
    }
}
