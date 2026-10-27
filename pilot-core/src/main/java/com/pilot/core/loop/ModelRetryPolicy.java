package com.pilot.core.loop;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型请求失败时的重试判定。
 *
 * <p>断网、超时、429、5xx 值得重试；401/403/404/400 重试多少次都一样，立刻失败。
 * 这组判断放在这里而不是 AgentLoop 里，是因为它是纯函数、边界又多（异常链、状态码文本），
 * 单独拿出来可以直接覆盖每种情况。
 */
final class ModelRetryPolicy {
    /** 最多再试 3 次，间隔 2 秒起、翻倍、不超过 30 秒。 */
    static final int MAX_TRANSIENT_RETRIES = 3;
    private static final long INITIAL_MS = 2_000L;
    private static final long MAX_MS = 30_000L;
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d+)");

    private ModelRetryPolicy() {
    }

    /** 第 {@code attempt} 次重试前等多久（attempt 从 1 开始）。 */
    static long delayMs(int attempt) {
        int shift = Math.min(Math.max(attempt, 1) - 1, 4);
        return Math.min(INITIAL_MS << shift, MAX_MS);
    }

    /**
     * 这个错误值不值得重试。
     *
     * <p>按消息文本判断是因为模型客户端把状态码和原因都拼进了异常消息；
     * 再兜一层 {@link IOException} 异常链，覆盖连接被重置这类没有状态码的情况。
     */
    static boolean isTransient(RuntimeException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        // 主动中断（取消、超时杀线程）不该重试，否则用户点了停止还要再等一轮
        if (message.contains("被中断")) {
            return false;
        }
        if (message.contains("模型请求失败")) {
            return true;
        }
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) {
            int code = Integer.parseInt(status.group(1));
            return code == 408 || code == 429 || code >= 500;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
