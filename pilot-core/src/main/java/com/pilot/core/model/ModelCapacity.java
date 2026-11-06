package com.pilot.core.model;

import java.util.Locale;

/** 显式运行容量策略；不根据模型别名猜测厂商真实窗口。字符内存上限另由上下文策略管理。 */
public record ModelCapacity(long contextTokens, long inputTokens, int outputTokens, long marginTokens,
    int imageTokens) {
    public ModelCapacity {
        if (contextTokens < 1 || inputTokens < 1 || outputTokens < 1 || marginTokens < 0 || imageTokens < 1
            || outputTokens >= contextTokens || marginTokens >= contextTokens - outputTokens) {
            throw new IllegalArgumentException("模型容量配置无效：窗口必须大于输出预留与安全余量之和");
        }
    }

    public long inputBudget() {
        return Math.min(inputTokens, contextTokens - outputTokens - marginTokens);
    }

    public static ModelCapacity configured() {
        return new ModelCapacity(value("context-tokens", 128000), value("input-tokens", 128000),
            Math.toIntExact(value("output-tokens", 16000)), value("margin-tokens", 8000),
            Math.toIntExact(value("image-tokens", 8192)));
    }

    private static long value(String key, long fallback) {
        String property = System.getProperty("pilot.model." + key);
        String text = property == null ? System.getenv("PILOT_MODEL_" + key.toUpperCase(Locale.ROOT).replace('-', '_'))
            : property;
        return text == null || text.isBlank() ? fallback : Long.parseLong(text);
    }
}
