package com.pilot.core.loop;

/**
 * 取消信号。AgentLoop 在每个模型步骤与每次工具调用前检查，
 * 由接入层（HTTP 取消接口）翻转其状态。
 * 无法中断正在进行的模型请求，但能保证当前步骤结束后立即停止。
 */
@FunctionalInterface
public interface Cancellation {
    boolean cancelled();

    static Cancellation none() {
        return () -> false;
    }
}
