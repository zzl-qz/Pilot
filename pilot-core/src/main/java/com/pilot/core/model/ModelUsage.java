package com.pilot.core.model;

/**
 * 一次模型请求的 token 用量。
 *
 * <p>输入口径经过归一化：{@code inputTokens} 是本次请求的<b>总输入</b>，包含命中缓存与写入缓存
 * 的部分。各家端点的原始字段口径不同，差异在 {@link OpenAiCompatibleModelClient} 解析时抹平，
 * 下游（累计、展示、成本核算）一律按总输入理解：
 * <ul>
 *   <li>OpenAI 兼容：{@code prompt_tokens} 已经包含 {@code prompt_tokens_details.cached_tokens}，
 *       直接作为总输入；端点不返回缓存写入量，{@code cacheWriteTokens} 记 0。</li>
 *   <li>Anthropic 兼容：{@code input_tokens} 不含缓存部分，总输入 =
 *       {@code input_tokens + cache_read_input_tokens + cache_creation_input_tokens}。</li>
 * </ul>
 * 不做这层归一化，命中率会算错方向：OpenAI 风格下缓存是分子、Anthropic 风格下缓存是加数。
 *
 * <p>缓存命中率 = {@code cachedInputTokens / inputTokens}，是省钱效果的一等观测指标：
 * 命中的部分按缓存价计费，通常只有全价的十分之一量级（具体倍数以各家定价为准）。
 *
 * <p>估算出来的用量（{@code exact == false}）没有缓存观测，两个缓存字段恒为 0，
 * {@link #cacheHitRatio()} 也返回 0——避免把「没观测到」显示成「没命中」以外的误导结论。
 *
 * @param inputTokens       总输入 token（含缓存部分）
 * @param outputTokens      输出 token（包含 reasoning 时由兼容端点决定是否计入）
 * @param reasoningTokens   端点单独返回的 reasoning token；没有则为 0
 * @param cachedInputTokens 命中缓存、按缓存价计费的输入 token（inputTokens 的子集）
 * @param cacheWriteTokens  本次写入缓存、按溢价计费的输入 token（inputTokens 的子集）
 * @param exact             true 表示来自模型接口 usage；false 表示本地保守估算
 */
public record ModelUsage(long inputTokens, long outputTokens, long reasoningTokens,
                         long cachedInputTokens, long cacheWriteTokens, boolean exact) {

    /** 兼容没有缓存观测的调用方（估算路径、第三方 ModelClient）：缓存部分视为 0。 */
    public ModelUsage(long inputTokens, long outputTokens, long reasoningTokens, boolean exact) {
        this(inputTokens, outputTokens, reasoningTokens, 0L, 0L, exact);
    }

    public ModelUsage {
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        reasoningTokens = Math.max(0L, reasoningTokens);
        cachedInputTokens = Math.max(0L, cachedInputTokens);
        cacheWriteTokens = Math.max(0L, cacheWriteTokens);
        // 端点字段偶尔自相矛盾（缓存量大于总输入）：钳到总输入内，先保命中、余量算写入，
        // 保证命中率不超过 100%，也让后续成本核算不会算出负数。
        long cachedTotal = Math.min(inputTokens, cachedInputTokens + cacheWriteTokens);
        cachedInputTokens = Math.min(cachedInputTokens, cachedTotal);
        cacheWriteTokens = cachedTotal - cachedInputTokens;
    }

    public static ModelUsage unknown() {
        return new ModelUsage(0, 0, 0, 0, 0, false);
    }

    public boolean present() {
        return inputTokens > 0 || outputTokens > 0 || reasoningTokens > 0;
    }

    /** 缓存命中占比（0~1）；没有精确 usage 或总输入为 0 时为 0。 */
    public double cacheHitRatio() {
        if (!exact || inputTokens <= 0) {
            return 0d;
        }
        return cachedInputTokens / (double) inputTokens;
    }

    /** 按全价计费的输入部分：总输入减去命中缓存与写入缓存的部分。 */
    public long fullPriceInputTokens() {
        return Math.max(0L, inputTokens - cachedInputTokens - cacheWriteTokens);
    }
}
