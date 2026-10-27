package com.pilot.server.session;

import com.pilot.core.loop.Attachment;

import java.util.List;
import java.util.Optional;

/**
 * 一次运行的启动器。控制器只依赖这个接口，真正跑模型的是 {@code AgentRunService}。
 */
public interface RunStarter {
    void start(String sessionId, String runId, String input);

    /**
     * 带附件的启动。默认实现忽略附件，测试里的空实现不用处理附件。
     * {@code AgentRunService} 会把附件挂到本次用户消息上。
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

    /** 还没被当前运行拿走的用户消息，按入队顺序。 */
    default List<String> queued(String sessionId) {
        return List.of();
    }

    /** 改队列里的某一句。下标从 0 开始。 */
    default boolean editQueued(String sessionId, int index, String text) {
        return false;
    }

    /** 从队列里拿掉某一句。下标从 0 开始。 */
    default boolean removeQueued(String sessionId, int index) {
        return false;
    }

    /**
     * 编辑已发出的用户消息前，把对话记忆裁到这句之前。
     *
     * @param occurrence    这句原文在用户消息里是第几次出现，从 1 开始
     * @param originalInput 改之前的原文，用来在记忆里定位
     */
    default void rewindBeforeUserInput(String sessionId, int occurrence, String originalInput) {
    }
}
