package com.pilot.core.loop;

import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型请求失败时的重试判定。
 *
 * <p>按失败分类处理，而不是一律“失败几次就放弃”：
 * <ul>
 *   <li>网络类（连不上、超时、连接中途断开）：一直退避重连，只有用户取消或线程中断才结束。
 *       模型偶尔抖动一次不该让整个任务判死。</li>
 *   <li>服务端暂时性故障（408、429、5xx）：有限次重试，避免对一台持续故障的服务空转。</li>
 *   <li>鉴权、权限、模型不支持、请求或响应结构非法：重试多少次都一样，立刻失败。</li>
 * </ul>
 *
 * <p>判断放在这里而不是 AgentLoop 里，是因为它是纯函数、边界又多（异常链、状态码文本），
 * 单独拿出来可以直接覆盖每种情况。
 */
public final class ModelRetryPolicy {
    /** 服务端暂时性故障最多再试 3 次，间隔 2 秒起、翻倍、不超过 30 秒。 */
    public static final int MAX_TRANSIENT_RETRIES = 3;
    /** 网络类失败不设上限：一直重连，直到用户取消。 */
    public static final int UNLIMITED_RETRIES = Integer.MAX_VALUE;
    private static final long INITIAL_MS = 2_000L;
    private static final long MAX_MS = 30_000L;
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d+)");

    private ModelRetryPolicy() {
    }

    /** 第 {@code attempt} 次重试前等多久（attempt 从 1 开始）。 */
    public static long delayMs(int attempt) {
        int shift = Math.min(Math.max(attempt, 1) - 1, 4);
        return Math.min(INITIAL_MS << shift, MAX_MS);
    }

    /**
     * 这类失败最多重试几次。{@link ModelFailureKind#NETWORK} 不设上限，
     * 不可重试的分类返回 0。
     */
    public static int maxRetries(ModelFailureKind kind) {
        if (kind == null) {
            return 0;
        }
        return switch (kind) {
            case NETWORK -> UNLIMITED_RETRIES;
            case TRANSIENT -> MAX_TRANSIENT_RETRIES;
            case INTERRUPTED, PERMANENT -> 0;
        };
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
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) {
            int code = Integer.parseInt(status.group(1));
            return code == 408 || code == 429 || code >= 500
                    ? ModelFailureKind.TRANSIENT
                    : ModelFailureKind.PERMANENT;
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
