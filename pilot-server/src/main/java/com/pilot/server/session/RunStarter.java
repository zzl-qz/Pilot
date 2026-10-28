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

    default void start(String sessionId, String runId, String input, List<Attachment> attachments,
                       TaskControlService.Requirements task) {
        start(sessionId, runId, input, attachments);
    }

    /** 普通追加仅排队；保留默认转发以兼容已有启动器。 */
    default boolean followup(String sessionId, String text) { return steer(sessionId, text); }

    /** 暂停在当前安全步骤结束后生效，保留任务及排队消息。 */
    default boolean pause(String runId) { return false; }

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
     * 抢占式申请一次运行的槽位：同一会话已经有运行在跑时返回空。
     *
     * <p>控制器先占位、再发布 {@code run.started}、最后调用 {@code start}，这样「检查有没有在跑」
     * 与「真的开跑」之间不再是两步无锁操作，双击发送也不会起两个 run。占位后启动失败时
     * 必须调用 {@link #abandon(String)} 释放，否则会话会被永久占住。
     *
     * <p>默认实现直接发号，方便测试里的空实现继续按原样工作。
     *
     * @return 本次运行的 runId
     */
    default Optional<String> claimRun(String sessionId) {
        if (activeRun(sessionId).isPresent()) {
            return Optional.empty();
        }
        return Optional.of("run_" + java.util.UUID.randomUUID());
    }

    /** 释放 {@link #claimRun(String)} 占到但没能启动的槽位。 */
    default void abandon(String runId) {
    }

    /** 独占文件恢复/切分支期间的工作区，abandon 时释放；其他会话不能趁此启动。 */
    default boolean lockWorkspace(String operationId, java.nio.file.Path workspace) {
        return true;
    }

    /** 删除会话时忘掉它留下的内存状态（队列、续跑计数等）。 */
    default void forget(String sessionId) {
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
     * 立即纠正当前任务：在下一个安全步骤注入，不创建独立任务。
     *
     * @return false 表示当前没有活动运行，调用方应按普通消息处理
     */
    default boolean steer(String sessionId, String text) {
        return false;
    }

    /** 将纠正绑定到用户所见的运行，任务交接后不转发给下一件事。 */
    default boolean steer(String sessionId, String expectedRunId, String text) {
        return activeRun(sessionId).filter(expectedRunId::equals).isPresent() && steer(sessionId, text);
    }

    /** 最后一项验收完成并释放编辑槽位后，尝试启动等待中的下一件事。 */
    default void requirementsUpdated(String sessionId) { }

    /** 等待当前任务结束后执行的用户消息，按入队顺序。 */
    default List<String> queued(String sessionId) {
        return List.of();
    }

    default void discardSteering(String sessionId) { }

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
