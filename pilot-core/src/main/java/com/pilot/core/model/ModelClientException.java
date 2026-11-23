package com.pilot.core.model;

/**
 * 模型调用失败（网络、HTTP 状态、响应结构或工具参数解析）。
 *
 * <p>带上 {@link ModelFailureKind} 让上层重试决策不依赖消息文本：网络中断可以一直重连，
 * 鉴权/权限/模型不支持则立刻失败。分类为空表示没有显式归类，上层退回文本判定，
 * 以兼容测试里的简化异常和第三方 {@link ModelClient} 实现。
 */
public class ModelClientException extends RuntimeException {
    private final ModelFailureKind kind;
    private final int statusCode;
    private final long retryAfterMs;

    public ModelClientException(String message) {
        this(null, message, null);
    }

    public ModelClientException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public ModelClientException(ModelFailureKind kind, String message) {
        this(kind, message, null);
    }

    public ModelClientException(ModelFailureKind kind, String message, Throwable cause) {
        this(kind, 0, -1L, message, cause);
    }

    private ModelClientException(ModelFailureKind kind, int statusCode, long retryAfterMs, String message,
        Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = Math.max(0, statusCode);
        this.retryAfterMs = retryAfterMs;
    }

    /** 带 HTTP 状态码与服务端 Retry-After 毫秒提示的结构化模型异常。 */
    public static ModelClientException httpError(ModelFailureKind kind, int statusCode, long retryAfterMs,
        String message) {
        return new ModelClientException(kind, statusCode, retryAfterMs, message, null);
    }

    /** 失败分类；{@code null} 表示未显式分类。 */
    public ModelFailureKind kind() {
        return kind;
    }

    /** HTTP 状态码；非 HTTP 响应错误时为 {@code 0}。 */
    public int statusCode() {
        return statusCode;
    }

    /** 服务端通过 Retry-After 返回的退避毫秒数；未提供时为 {@code -1}。 */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
