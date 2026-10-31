package com.pilot.core.loop;

import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;
import com.pilot.core.model.ModelFailureSignals;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型请求失败时的重试判定。
 *
 * <p>原则上“能连上但没被受理”才放弃，其余都可以重试，但放慢节奏：
 * <ul>
 *   <li>网络类（连不上、超时、连接中途断开）与服务端暂时性故障（408、429、5xx）：
 *       交给 {@link RetryBudget} 做指数退避、抖动与预算控制，不再固定节奏地无限重试。</li>
 *   <li>明确的鉴权/权限、余额或额度耗尽、模型不支持、请求或响应结构非法：重试不会变好，立刻失败。</li>
 * </ul>
 *
 * <p>判断放在这里而不是 AgentLoop 里，是因为它是纯函数、边界又多（异常链、状态码文本），
 * 单独拿出来可以直接覆盖每种情况。
 */
public final class ModelRetryPolicy {
    /** 退避基准：第一次重试从此起步，之后逐次翻倍直到上限。 */
    public static final long RETRY_DELAY_MS = 1_000L;
    /** 单次退避上限，避免长故障时等待过久。 */
    public static final long MAX_RETRY_DELAY_MS = 30_000L;
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d+)");

    private ModelRetryPolicy() {
    }

    /** 第 {@code attempt} 次重试的基准等待（不含抖动）。 */
    public static long delayMs(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long delay = RETRY_DELAY_MS << Math.min(exponent, 20);
        return Math.min(MAX_RETRY_DELAY_MS, delay);
    }

    /** 这个错误值不值得重试。 */
    public static boolean isTransient(RuntimeException error) {
        return classify(error).retryable();
    }

    /**
     * 判定失败分类。
     *
     * <p>优先用 {@link ModelClientException#kind()} 的结构化分类；没有分类时退回消息文本判断，
     * 因为历史异常与测试替身只带消息（状态码和原因一般都拼在消息里）。
     * 再兜一层 {@link IOException} 异常链，覆盖连接被重置这类没有状态码的情况。
     */
    public static ModelFailureKind classify(RuntimeException error) {
        if (error == null) {
            return ModelFailureKind.PERMANENT;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelClientException client && client.kind() != null) {
                return client.kind();
            }
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        // 主动中断（取消、超时杀线程）不该重试，否则用户点了停止还要再等一轮
        if (message.contains("被中断")) {
            return ModelFailureKind.INTERRUPTED;
        }
        // 余额不足、额度耗尽重试多少次都一样
        if (ModelFailureSignals.billingOrQuota(message)) {
            return ModelFailureKind.PERMANENT;
        }
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) {
            return ModelFailureSignals.statusKind(Integer.parseInt(status.group(1)), message);
        }
        if (message.contains("模型请求失败") || message.contains("流式模型请求失败")) {
            return ModelFailureKind.NETWORK;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return ModelFailureKind.NETWORK;
            }
        }
        return ModelFailureKind.PERMANENT;
    }
}
