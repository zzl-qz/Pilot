package com.pilot.core.model;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * 按本次模型配置组装容量。空的字段回落到进程默认值；输出预留过大时夹到窗口能放下的范围，避免配置写错直接把服务打崩。
     */
    public static ModelCapacity of(Long contextTokens, Integer outputTokens) {
        return of(contextTokens, contextTokens, outputTokens);
    }

    public static ModelCapacity of(Long contextTokens, Long inputTokens, Integer outputTokens) {
        ModelCapacity defaults = configured();
        long context = contextTokens == null || contextTokens < 1 ? defaults.contextTokens() : contextTokens;
        long input = inputTokens == null || inputTokens < 1 ? defaults.inputTokens() : inputTokens;
        int output = outputTokens == null || outputTokens < 1 ? defaults.outputTokens() : outputTokens;
        int cappedOutput = (int)Math.min(output, Math.max(1, context / 2));
        long margin = Math.min(defaults.marginTokens(), Math.max(0, context - cappedOutput - 1));
        return new ModelCapacity(context, input, cappedOutput, margin, defaults.imageTokens());
    }

    private static final AtomicReference<ModelCapacity> ACTIVE = new AtomicReference<>();

    /** 当前模型设置覆盖进程默认值。没有设置时用 {@link #configured()}。 */
    public static ModelCapacity active() {
        ModelCapacity value = ACTIVE.get();
        return value == null ? configured() : value;
    }

    public static void activate(ModelCapacity capacity) {
        ACTIVE.set(capacity);
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
