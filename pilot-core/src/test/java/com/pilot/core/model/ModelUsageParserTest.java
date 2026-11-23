package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 各协议把用量字段放在不同命名、不同层级下。解析错不会抛异常，只会让成本面板显示一个看着合理、
 * 实际反向的数字，所以每条断言都写死具体数值。
 */
class ModelUsageParserTest {
    private final ObjectMapper json = new ObjectMapper();

    private ModelUsage parse(String value) {
        return ModelUsageParser.parse(json.readTree(value));
    }

    @Test
    void rejectsAbsentNullAndNonObjectUsage() {
        assertNull(ModelUsageParser.parse(null), "没有 usage 字段时不能合成用量");
        assertNull(ModelUsageParser.parse(json.readTree("null")));
        assertNull(ModelUsageParser.parse(json.readTree("{}").path("usage")), "MissingNode 也是没报");
        assertNull(parse("[]"), "顶层不是对象时字段全缺，等同于没报");
    }

    @Test
    void parsesOpenAiChatCompletionsUsage() {
        ModelUsage usage = parse(
            "{\"prompt_tokens\":1000,\"completion_tokens\":40," + "\"prompt_tokens_details\":{\"cached_tokens\":800},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":12}}");

        assertEquals(1000, usage.inputTokens(), "prompt_tokens 已含缓存，不能再加一遍");
        assertEquals(40, usage.outputTokens());
        assertEquals(12, usage.reasoningTokens());
        assertEquals(800, usage.cachedInputTokens());
        assertEquals(0, usage.cacheWriteTokens());
        assertEquals(0.8, usage.cacheHitRatio(), 1e-9);
        assertTrue(usage.exact());
        assertTrue(usage.cacheReadObserved());
        assertFalse(usage.cacheWriteObserved(), "OpenAI 不报缓存写入");
    }

    @Test
    void treatsOpenAiChatWithoutCacheDetailsAsUnobserved() {
        ModelUsage usage = parse("{\"prompt_tokens\":1000,\"completion_tokens\":10}");

        assertTrue(usage.exact());
        assertFalse(usage.cacheReadObserved(), "字段缺失不能当成实测零命中");
        assertFalse(usage.cacheWriteObserved());
        assertEquals(0, usage.cachedInputTokens());
        assertEquals(0d, usage.cacheHitRatio());
    }

    @Test
    void parsesOpenAiResponsesUsage() {
        ModelUsage usage =
            parse("{\"input_tokens\":900,\"output_tokens\":30," + "\"input_tokens_details\":{\"cached_tokens\":600},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":7}}");

        assertEquals(900, usage.inputTokens());
        assertEquals(30, usage.outputTokens());
        assertEquals(7, usage.reasoningTokens());
        assertEquals(600, usage.cachedInputTokens());
        assertTrue(usage.cacheReadObserved());
        assertTrue(usage.exact());
    }

    @Test
    void addsAnthropicCacheFieldsOnTopOfInputTokens() {
        ModelUsage usage = parse("{\"input_tokens\":100,\"output_tokens\":10,"
            + "\"cache_read_input_tokens\":700,\"cache_creation_input_tokens\":200}");

        assertEquals(1000, usage.inputTokens(), "Anthropic 的 input_tokens 不含缓存读写");
        assertEquals(700, usage.cachedInputTokens());
        assertEquals(200, usage.cacheWriteTokens());
        assertEquals(100, usage.fullPriceInputTokens(), "全价部分只有 input_tokens 本身");
        assertTrue(usage.cacheReadObserved());
        assertTrue(usage.cacheWriteObserved());
        assertTrue(usage.exact());
    }

    @Test
    void addsAnthropicWriteOnlyCacheBackIntoInput() {
        ModelUsage usage = parse("{\"input_tokens\":100,\"output_tokens\":10,\"cache_creation_input_tokens\":50}");

        assertEquals(150, usage.inputTokens());
        assertEquals(50, usage.cacheWriteTokens());
        assertEquals(0, usage.cachedInputTokens());
        assertFalse(usage.cacheReadObserved());
        assertTrue(usage.cacheWriteObserved());
    }

    @Test
    void keepsPromptTotalWhenGatewayMixesProtocolFields() {
        // 兼容网关同时给 prompt_tokens（含缓存的总额）和 Anthropic 缓存字段：仍以总额为准
        ModelUsage usage = parse("{\"prompt_tokens\":1000,\"completion_tokens\":10,"
            + "\"cache_read_input_tokens\":700,\"cache_creation_input_tokens\":200}");

        assertEquals(1000, usage.inputTokens());
        assertEquals(100, usage.fullPriceInputTokens());
        assertTrue(usage.cacheReadObserved());
        assertTrue(usage.cacheWriteObserved());
    }

    @Test
    void sumsDeepSeekHitAndMissWhenTotalMissing() {
        ModelUsage usage =
            parse("{\"prompt_cache_hit_tokens\":800,\"prompt_cache_miss_tokens\":200," + "\"completion_tokens\":10}");

        assertEquals(1000, usage.inputTokens(), "只报命中/未命中时两者之和才是总输入");
        assertEquals(800, usage.cachedInputTokens());
        assertEquals(200, usage.fullPriceInputTokens());
        assertTrue(usage.cacheReadObserved());
        assertTrue(usage.exact(), "命中与未命中两段齐全也等于输入已报");
    }

    @Test
    void doesNotDoubleCountDeepSeekFieldsWhenPromptTotalPresent() {
        ModelUsage usage = parse("{\"prompt_tokens\":1000,\"completion_tokens\":10,"
            + "\"prompt_cache_hit_tokens\":800,\"prompt_cache_miss_tokens\":200}");

        assertEquals(1000, usage.inputTokens());
        assertEquals(800, usage.cachedInputTokens());
        assertEquals(200, usage.fullPriceInputTokens());
    }

    @Test
    void appliesDeepSeekSplitInsteadOfAnthropicSumWhenBothPresent() {
        // 两套协议字段混在一个响应里：命中/未命中两段已构成总输入，不再叠加 Anthropic 缓存量
        ModelUsage usage = parse("{\"cache_read_input_tokens\":700,\"prompt_cache_hit_tokens\":300,"
            + "\"prompt_cache_miss_tokens\":100,\"output_tokens\":5}");

        assertEquals(400, usage.inputTokens());
        assertEquals(300, usage.cachedInputTokens());
        assertTrue(usage.exact());
    }

    @Test
    void prefersDetailsOverFlatCacheFields() {
        ModelUsage usage = parse("{\"prompt_tokens\":1000,\"completion_tokens\":10,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":800},\"cached_tokens\":10,"
            + "\"prompt_cache_hit_tokens\":100}");

        assertEquals(800, usage.cachedInputTokens(), "details 里的命中量最权威");
    }

    @Test
    void fallsBackToPromptCacheHitThenAnthropicThenFlatField() {
        assertEquals(800, parse("{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_cache_hit_tokens\":800,"
            + "\"cache_read_input_tokens\":5,\"cached_tokens\":10}").cachedInputTokens());
        assertEquals(700, parse("{\"prompt_tokens\":1000,\"completion_tokens\":10,\"cache_read_input_tokens\":700,"
            + "\"cached_tokens\":10}").cachedInputTokens());
        assertEquals(300,
            parse("{\"prompt_tokens\":600,\"completion_tokens\":17,\"cached_tokens\":300}").cachedInputTokens());
    }

    @Test
    void keepsExplicitZeroCachedTokensInsteadOfFallingBack() {
        ModelUsage usage = parse("{\"input_tokens\":1000,\"output_tokens\":10,\"cached_tokens\":900,"
            + "\"input_tokens_details\":{\"cached_tokens\":0}}");

        assertEquals(0, usage.cachedInputTokens(), "显式零值不能回落到顶层同义字段");
        assertTrue(usage.cacheReadObserved(), "零值也是观测到的值");
    }

    @Test
    void fallsBackToAlternativeDetailsObjectWhenFirstIsNotAnObject() {
        ModelUsage cached = parse("{\"prompt_tokens\":1000,\"completion_tokens\":10,"
            + "\"prompt_tokens_details\":\"none\",\"input_tokens_details\":{\"cached_tokens\":400}}");
        ModelUsage reasoning = parse("{\"prompt_tokens\":10,\"completion_tokens\":1,"
            + "\"completion_tokens_details\":[],\"output_tokens_details\":{\"reasoning_tokens\":3}}");

        assertEquals(400, cached.cachedInputTokens());
        assertTrue(cached.cacheReadObserved());
        assertEquals(3, reasoning.reasoningTokens());
    }

    @Test
    void ignoresNonNumericAndNegativeFields() {
        ModelUsage usage =
            parse("{\"prompt_tokens\":-5,\"input_tokens\":10,\"completion_tokens\":\"n/a\"," + "\"output_tokens\":2}");

        assertEquals(10, usage.inputTokens(), "负数按缺失处理，退到兼容字段");
        assertEquals(2, usage.outputTokens());
        assertTrue(usage.exact());
        assertFalse(usage.cacheReadObserved());
    }

    @Test
    void treatsAllZeroUsageWithoutTotalsAsMissing() {
        assertNull(parse("{}"));
        assertNull(parse("{\"completion_tokens\":0}"), "全零又没有总量字段，等同于没报");
        assertNull(parse("{\"output_tokens_details\":{\"reasoning_tokens\":0}}"));
    }

    @Test
    void keepsExplicitZeroTotalsInsteadOfDroppingUsage() {
        ModelUsage usage = parse("{\"prompt_tokens\":0,\"completion_tokens\":0}");

        assertEquals(0, usage.inputTokens(), "总量字段存在就是端点报了零，不能返回 null");
        assertEquals(0, usage.outputTokens());
        assertTrue(usage.exact());
        assertFalse(usage.present());
    }

    @Test
    void requiresBothDirectionsBeforeCallingUsageExact() {
        ModelUsage inputOnly = parse("{\"input_tokens\":1000}");
        ModelUsage outputOnly = parse("{\"completion_tokens\":10}");

        assertEquals(1000, inputOnly.inputTokens());
        assertFalse(inputOnly.exact(), "没有输出字段就不算精确用量");
        assertEquals(10, outputOnly.outputTokens());
        assertFalse(outputOnly.exact());
    }

    @Test
    void reportsReasoningOnlyUsageWithoutInputOrOutput() {
        ModelUsage usage = parse("{\"output_tokens_details\":{\"reasoning_tokens\":5}}");

        assertEquals(5, usage.reasoningTokens());
        assertEquals(0, usage.outputTokens());
        assertFalse(usage.exact());
        assertTrue(usage.present(), "只有推理量也是端点报的用量");
    }

    @Test
    void saturatesCacheSumsInsteadOfOverflowing() {
        ModelUsage usage = parse("{\"cache_read_input_tokens\":" + Long.MAX_VALUE + ",\"cache_creation_input_tokens\":"
            + Long.MAX_VALUE + ",\"output_tokens\":1}");

        assertEquals(Long.MAX_VALUE, usage.inputTokens(), "溢出时钉在 Long.MAX_VALUE");
        assertEquals(Long.MAX_VALUE, usage.cachedInputTokens());
        assertEquals(0, usage.cacheWriteTokens());
    }
}
