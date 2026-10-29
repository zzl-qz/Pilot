package com.pilot.core.event;

/**
 * 当前 run 的累计模型用量。运行中会多次更新，界面不必等到结束才显示。
 *
 * @param modelRequests 模型请求次数
 * @param inputTokens   累计输入 token
 * @param outputTokens  累计输出 token
 * @param contextTokens 最近一次请求的上下文 token
 * @param contextLimit  当前上下文预算
 * @param exact         所有累计 token 是否都来自端点 usage；false 表示至少含本地估算
 */
public record RunUsageUpdated(int modelRequests, long inputTokens, long outputTokens,
                              int contextTokens, int contextLimit, boolean exact) implements SessionEvent {
    public RunUsageUpdated {
        modelRequests = Math.max(0, modelRequests);
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        contextTokens = Math.max(0, contextTokens);
        contextLimit = Math.max(1, contextLimit);
    }

    @Override
    public EventType type() {
        return EventType.RUN_USAGE_UPDATED;
    }
}
