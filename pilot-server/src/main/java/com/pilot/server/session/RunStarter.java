package com.pilot.server.session;

import java.util.Optional;

/**
 * 一次运行的启动器。控制器只依赖该接口，具体实现由 {@code pilot.agent.mode} 决定：
 * mock 走离线剧本，real 走 {@code AgentLoop}。
 */
public interface RunStarter {
    void start(String sessionId, String runId, String input);

    /**
     * 请求取消一次运行。
     *
     * @return 是否找到并接受了取消请求（未知或已结束的 run 返回 false）
     */
    default boolean cancel(String runId) {
        return false;
    }

    /** 当前会话是否还有运行在进行，用于断线重连后的状态校准。 */
    default Optional<String> activeRun(String sessionId) {
        return Optional.empty();
    }

    /**
     * 重新生成：用上一次的用户输入再跑一遍。真实实现会先丢弃上一轮对话，
     * 避免把同一条用户消息重复放进历史。
     */
    default void regenerate(String sessionId, String runId, String input) {
        start(sessionId, runId, input);
    }
}
