package com.pilot.core.model;

/**
 * 模型调用失败的分类，决定上层要不要继续重试。
 *
 * <p>客户端负责在抛出异常时给出分类，重试策略只按分类决策，不再靠消息文本猜：
 * 连不上或连接中途断开可以一直重连，鉴权、权限、模型不支持这类错误重试多少次都一样。
 */
public enum ModelFailureKind {
    /** 用户取消或线程被打断：不是模型的问题，立刻结束，不再发请求。 */
    INTERRUPTED(false),
    /** 连不上或连接中途断开（超时、连接重置、DNS、断网）：一直重试，避免一次抖动终结任务。 */
    NETWORK(true),
    /** 连上了但服务端暂时性故障（408/429/5xx）：同样一直重试，节奏放慢到约 3 秒一次。 */
    TRANSIENT(true),
    /** 鉴权、权限、余额或额度耗尽、模型不支持、请求非法、响应结构非法：重试不会变好，立刻失败。 */
    PERMANENT(false);

    private final boolean retryable;

    ModelFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
