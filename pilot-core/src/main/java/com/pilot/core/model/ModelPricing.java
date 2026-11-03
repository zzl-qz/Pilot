package com.pilot.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 每百万 token 单价。缺失不是免费，计算结果也不是厂商账单。 */
public record ModelPricing(String currency, BigDecimal input, BigDecimal cachedInput,
                           BigDecimal cacheWrite, BigDecimal output, String source) {
    public ModelPricing {
        currency = currency == null || currency.isBlank() ? "USD" : currency.strip().toUpperCase(java.util.Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("币种必须是三个英文字母");
        for (BigDecimal rate : new BigDecimal[]{input, cachedInput, cacheWrite, output}) {
            if (rate != null && (rate.signum() < 0 || rate.compareTo(new BigDecimal("1000000")) > 0))
                throw new IllegalArgumentException("每百万 token 单价必须在 0 到 1000000 之间");
        }
        source = source == null ? "" : source.strip();
        if (source.length() > 300) throw new IllegalArgumentException("报价来源不能超过 300 个字符");
    }
    public static ModelPricing unknown() { return new ModelPricing("USD", null, null, null, null, ""); }

    public Cost estimate(ModelUsage usage) {
        if (usage == null || !usage.exact()) return Cost.unknown(currency, "模型未返回完整用量");
        if ((!usage.cacheReadObserved() && !same(input, cachedInput))
                || (!usage.cacheWriteObserved() && !same(input, cacheWrite)))
            return Cost.unknown(currency, "缓存计费用量不完整");
        BigDecimal plain = charge(usage.fullPriceInputTokens(), input);
        BigDecimal read = charge(usage.cachedInputTokens(), cachedInput);
        BigDecimal write = charge(usage.cacheWriteTokens(), cacheWrite);
        BigDecimal generated = charge(usage.outputTokens(), output);
        if (plain == null || read == null || write == null || generated == null)
            return Cost.unknown(currency, "尚未配置完整模型单价");
        return new Cost(currency, plain.add(read).add(write).add(generated), plain, read, write, generated,
                source.isBlank() ? "配置单价" : source, "按模型用量与单价估算，以服务商账单为准");
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) == 0; }
    private static BigDecimal charge(long tokens, BigDecimal price) {
        if (tokens == 0) return BigDecimal.ZERO;
        return price == null ? null : price.multiply(BigDecimal.valueOf(tokens)).divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
    }
    public record Cost(String currency, BigDecimal total, BigDecimal input, BigDecimal cachedInput,
                       BigDecimal cacheWrite, BigDecimal output, String source, String note) {
        public static Cost unknown(String currency, String note) {
            return new Cost(currency, null, null, null, null, null, "", note);
        }
    }
}
