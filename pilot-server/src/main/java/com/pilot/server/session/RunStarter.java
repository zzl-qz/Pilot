package com.pilot.server.session;

import com.pilot.core.loop.Attachment;

import java.util.List;
import java.util.Optional;

/**
 * 一次运行的启动器。控制器只依赖该接口，具体实现由 {@code pilot.agent.mode} 决定：
 * mock 走离线剧本，real 走 {@code AgentLoop}。
 */
public interface RunStarter {
    void start(String sessionId, String runId, String input);

    /**
     * 带附件的启动。默认实现忽略附件，保证 mock 与测试实现不被拖累；
     * 真实实现（{@code AgentRunService}）会把它挂到本次用户消息上。
     */
    default void start(String sessionId, String runId, String input, List<Attachment> attachments) {
        start(sessionId, runId, input);
    }

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

    /**
     * 续跑：不追加用户消息，直接用已经落盘的对话记忆继续。
     * 与 {@link #regenerate} 的区别是它不改变历史语义，只恢复被中断的那一轮。
     */
    default void resume(String sessionId, String runId) {
        start(sessionId, runId, "继续上一次未完成的任务");
    }

    /** 该会话是否存在可续跑的记忆。 */
    default boolean resumable(String sessionId) {
        return false;
    }

    /**
     * 运行中追加输入：入队等下一轮消费，不新开 run。
     *
     * @return false 表示当前没有活动运行，调用方应按普通消息处理
     */
    default boolean steer(String sessionId, String text) {
        return false;
    }
}
