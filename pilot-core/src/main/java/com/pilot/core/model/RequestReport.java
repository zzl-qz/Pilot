package com.pilot.core.model;

/** 一个真实请求尝试的不可变记录。只保存统计及摘要指纹，不保存提示词、凭据或端点 URL。 */
public record RequestReport(String id, String purpose, String model, String provider, long startedAt, long durationMs,
    Long firstTokenMs, String status, ModelUsage usage, ContextComposition context, ModelPricing.Cost cost,
    Prefix prefix) {
    /** 本地请求连续性检查，不等于服务商 KV 缓存命中。 */
    public record Prefix(String strategy, String fingerprint, int unchangedMessages, String change) {
    }
}
