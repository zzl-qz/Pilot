package com.pilot.core.model;

/**
 * 一次请求的归一化用量。总输入包含缓存读写；exact 表示输入与输出均来自端点。
 * cacheReadObserved / cacheWriteObserved 分别表示已取得相应字段，实测零值与字段缺失不混淆。
 * 兼容旧 JSON 的反序列化由 EventCodec 按缺省 false 处理。
 */
public record ModelUsage(long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens,
    long cacheWriteTokens, boolean exact, boolean cacheReadObserved, boolean cacheWriteObserved) {

    public ModelUsage(long input, long output, long reasoning, long cached, long written, boolean exact) {
        this(input, output, reasoning, cached, written, exact, exact, exact);
    }

    /** 兼容没有缓存观测的调用方（估算路径、第三方 ModelClient）：缓存部分视为 0。 */
    public ModelUsage(long inputTokens, long outputTokens, long reasoningTokens, boolean exact) {
        this(inputTokens, outputTokens, reasoningTokens, 0L, 0L, exact, false, false);
    }

    public ModelUsage {
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        reasoningTokens = Math.max(0L, reasoningTokens);
        cachedInputTokens = Math.max(0L, cachedInputTokens);
        cacheWriteTokens = Math.max(0L, cacheWriteTokens);
        // 端点字段偶尔自相矛盾（缓存量大于总输入）：钳到总输入内，先保命中、余量算写入，
        // 保证命中率不超过 100%，也让后续成本核算不会算出负数。
        cachedInputTokens = Math.min(cachedInputTokens, inputTokens);
        cacheWriteTokens = Math.min(cacheWriteTokens, inputTokens - cachedInputTokens);
        cacheReadObserved &= exact;
        cacheWriteObserved &= exact;
    }

    public static ModelUsage unknown() {
        return new ModelUsage(0, 0, 0, false);
    }

    public boolean present() {
        return inputTokens > 0 || outputTokens > 0 || reasoningTokens > 0;
    }

    /** 缓存命中占比（0~1）；没有精确 usage 或总输入为 0 时为 0。 */
    public double cacheHitRatio() {
        if (!cacheReadObserved || inputTokens <= 0) {
            return 0d;
        }
        return cachedInputTokens / (double)inputTokens;
    }

    /** 按全价计费的输入部分：总输入减去命中缓存与写入缓存的部分。 */
    public long fullPriceInputTokens() {
        return Math.max(0L, inputTokens - cachedInputTokens - cacheWriteTokens);
    }
}
