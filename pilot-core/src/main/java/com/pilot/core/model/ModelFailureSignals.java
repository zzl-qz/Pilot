package com.pilot.core.model;

import java.util.List;
import java.util.Locale;

/**
 * HTTP 状态码与错误文本里的失败信号。
 *
 * <p>客户端拿到响应时用它分类，重试策略在没有结构化分类时用它兜底，两边判断口径保持一致。
 * 只有「连上了，但请求本身不会被受理」才判成永久失败：鉴权/权限、余额或额度耗尽、
 * 模型不支持、请求或响应结构非法。其余能连通的故障都当成可以一直重试。
 */
public final class ModelFailureSignals {
    /** 明确的「没钱/额度耗尽/欠费」信号；命中就不要重试了。 */
    private static final List<String> BILLING_MARKERS = List.of("insufficient_quota", "exceeded your current quota",
        "billing_not_active", "billing hard limit", "payment required", "insufficient balance",
        "credit balance is too low", "quota has been exhausted", "余额不足", "额度已用尽", "额度已耗尽", "欠费");

    private ModelFailureSignals() {
    }

    /** 错误文本是否明确说明余额不足、额度耗尽或账户欠费。 */
    public static boolean billingOrQuota(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : BILLING_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * HTTP 状态码分类。
     *
     * <p>402 与「余额不足」的 429 属于没钱，重试不会变好；401/403/404/400/422 是权限、模型
     * 或请求本身的问题；408、429、5xx 只是服务端暂时性故障，值得一直重试。
     */
    public static ModelFailureKind statusKind(int statusCode, String body) {
        if (statusCode == 402 || billingOrQuota(body)) {
            return ModelFailureKind.PERMANENT;
        }
        if (contextOverflow(statusCode, body)) {
            return ModelFailureKind.CONTEXT_OVERFLOW;
        }
        return statusCode == 408 || statusCode == 429 || statusCode >= 500 ? ModelFailureKind.TRANSIENT
            : ModelFailureKind.PERMANENT;
    }

    /**
     * 各家兼容接口对「输入超过窗口」的说法不一样，但都是 400/413/422，而且重发同一份正文不会变好。
     */
    public static boolean contextOverflow(int statusCode, String body) {
        if (statusCode != 400 && statusCode != 413 && statusCode != 422) {
            return false;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : List.of("context_length_exceeded", "maximum context length", "prompt is too long",
            "too many tokens", "input is too long", "exceeds the context", "context window", "token limit", "上下文超限",
            "上下文长度")) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
