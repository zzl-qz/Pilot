package com.pilot.core.model;

/**
 * 一次模型请求的 token 用量。
 *
 * @param inputTokens     输入 token
 * @param outputTokens    输出 token（包含 reasoning 时由兼容端点决定是否计入）
 * @param reasoningTokens 端点单独返回的 reasoning token；没有则为 0
 * @param exact           true 表示来自模型接口 usage；false 表示本地保守估算
 */
public record ModelUsage(long inputTokens, long outputTokens, long reasoningTokens, boolean exact) {
    public ModelUsage {
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        reasoningTokens = Math.max(0L, reasoningTokens);
    }

    public static ModelUsage unknown() {
        return new ModelUsage(0, 0, 0, false);
    }

    public boolean present() {
        return inputTokens > 0 || outputTokens > 0 || reasoningTokens > 0;
    }
}
