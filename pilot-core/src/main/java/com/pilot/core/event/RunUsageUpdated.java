package com.pilot.core.event;

/**
 * 当前 run 的累计模型用量。运行中会多次更新，界面不必等到结束才显示。
 *
 * <p>{@code cachedInputTokens} 是累计命中缓存的输入 token，与 {@code inputTokens} 同一口径
 * （总输入含缓存），两者的比值即缓存命中率——省钱效果的一等观测指标。端点不返回缓存用量时
 * 恒为 0，此时命中率应显示为「未知」而不是 0%，详见消费方的展示约定。
 *
 * @param modelRequests      模型请求次数
 * @param inputTokens        累计输入 token（总输入，含缓存部分）
 * @param outputTokens       累计输出 token
 * @param cachedInputTokens  累计命中缓存的输入 token
 * @param contextTokens      最近一次请求的上下文 token
 * @param contextLimit       当前上下文预算
 * @param exact              所有累计 token 是否都来自端点 usage；false 表示至少含本地估算
 */
public record RunUsageUpdated(int modelRequests, long inputTokens, long outputTokens, long cachedInputTokens,
    int contextTokens, int contextLimit, boolean exact,
    com.pilot.core.model.RequestTotals.Summary details) implements SessionEvent {

    public RunUsageUpdated(int requests, long input, long output, long cached, int context, int limit, boolean exact) {
        this(requests, input, output, cached, context, limit, exact, null);
    }

    /** 兼容没有缓存观测的调用方：缓存命中视为 0。 */
    public RunUsageUpdated(int modelRequests, long inputTokens, long outputTokens, int contextTokens, int contextLimit,
        boolean exact) {
        this(modelRequests, inputTokens, outputTokens, 0L, contextTokens, contextLimit, exact);
    }

    public RunUsageUpdated {
        modelRequests = Math.max(0, modelRequests);
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        cachedInputTokens = Math.min(Math.max(0L, cachedInputTokens), inputTokens);
        contextTokens = Math.max(0, contextTokens);
        contextLimit = Math.max(1, contextLimit);
    }

    @Override
    public EventType type() {
        return EventType.RUN_USAGE_UPDATED;
    }
}
