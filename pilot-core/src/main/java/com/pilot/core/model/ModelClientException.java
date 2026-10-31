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
        super(message, cause);
        this.kind = kind;
    }

    /** 失败分类；{@code null} 表示未显式分类。 */
    public ModelFailureKind kind() {
        return kind;
    }
}
