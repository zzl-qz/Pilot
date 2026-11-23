package com.pilot.server.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pilot.core.message.Attachment;
import com.pilot.core.util.Sha256;

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

    /**
     * 指定技能启动：技能存在性由控制器校验，指引作为首条 user 消息注入由实现完成。
     * 默认转发到无技能版本，测试里的空实现不用改。
     */
    default void start(String sessionId, String runId, String input, List<Attachment> attachments,
        TaskControlService.Requirements task, String skill) {
        start(sessionId, runId, input, attachments, task);
    }

    default void start(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, TaskControlService.Requirements task) {
        start(sessionId, runId, input, attachments, task);
    }

    /**
     * 图片位置与技能同时支持的完整启动，控制器的合并链路走这里。
     * 默认忽略技能，测试里的空实现不用改。
     */
    default void start(String sessionId, String runId, String input, List<Attachment> attachments,
        List<Integer> imagePositions, TaskControlService.Requirements task, String skill) {
        start(sessionId, runId, input, attachments, imagePositions, task);
    }

    /** 启动选项参数对象，避免长参数列表继续膨胀。 */
    record StartOptions(List<Attachment> attachments, List<Integer> imagePositions,
        TaskControlService.Requirements task, String skill, String mode) {
        public StartOptions {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            imagePositions = imagePositions == null ? List.of() : List.copyOf(imagePositions);
        }
    }

    /**
     * 带显式执行模式（{@code "plan"} | {@code "act"}，缺省按 {@code "act"}）的完整启动。
     * 默认转发到无模式重载，测试里的空实现不用改。
     */
    default void start(String sessionId, String runId, String input, StartOptions options) {
        StartOptions opts = options == null ? new StartOptions(List.of(), List.of(), null, null, null) : options;
        start(sessionId, runId, input, opts.attachments(), opts.imagePositions(), opts.task(), opts.skill());
    }

    /** 排队消息附带的独立约束与验收条件；为空表示这一条沿用默认的空要求。 */
    record QueueRequirements(List<String> constraints, List<String> acceptance) {
        public QueueRequirements {
            constraints = constraints == null ? List.of()
                : constraints.stream().filter(item -> item != null && !item.isBlank()).map(String::strip).toList();
            acceptance = acceptance == null ? List.of()
                : acceptance.stream().filter(item -> item != null && !item.isBlank()).map(String::strip).toList();
        }

        public boolean empty() {
            return constraints.isEmpty() && acceptance.isEmpty();
        }

        public TaskControlService.Requirements toRequirements() {
            return new TaskControlService.Requirements(constraints,
                acceptance.stream().map(text -> new TaskControlService.Acceptance(text, false, "")).toList());
        }
    }

    /** 排队中的一条消息：正文已经内联了文本文件，这里只额外带需要多模态编码的图片与可选模式。 */
    record QueuedMessage(String content, List<Attachment> attachments, List<Integer> imagePositions,
        QueueRequirements requirements, String mode) {
        public QueuedMessage {
            content = content == null ? "" : content;
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            imagePositions = imagePositions == null ? List.of() : List.copyOf(imagePositions);
        }

        public QueuedMessage(String content, List<Attachment> attachments, List<Integer> imagePositions) {
            this(content, attachments, imagePositions, null, null);
        }

        public QueuedMessage(String content, List<Attachment> attachments, List<Integer> imagePositions,
            QueueRequirements requirements) {
            this(content, attachments, imagePositions, requirements, null);
        }
    }

    /** 带附件信息的排队清单，顺序与 queued 一致。 */
    default List<QueuedMessage> queuedMessages(String sessionId) {
        return queued(sessionId).stream().map(text -> new QueuedMessage(text, List.of(), List.of())).toList();
    }

    /** 普通追加仅排队；保留默认转发以兼容已有启动器。 */
    default boolean followup(String sessionId, String text) {
        return steer(sessionId, text);
    }

    /**
     * 带图片附件的追加。默认实现只认纯文本，带附件时返回 false，
     * 这样不支持附件的启动器不会悄悄丢掉用户的图片。
     */
    default boolean followup(String sessionId, String text, List<Attachment> attachments,
        List<Integer> imagePositions) {
        boolean plain = attachments == null || attachments.isEmpty();
        return plain && followup(sessionId, text);
    }

    /** 带独立约束/验收的追加；不支持要求的启动器退化为普通追加。 */
    default boolean followup(String sessionId, String text, List<Attachment> attachments, List<Integer> imagePositions,
        QueueRequirements requirements) {
        return followup(sessionId, text, attachments, imagePositions);
    }

    /** 带显式执行模式（plan/act）的追加；默认忽略模式以兼容已有启动器。 */
    default boolean followup(String sessionId, String text, List<Attachment> attachments, List<Integer> imagePositions,
        QueueRequirements requirements, String mode) {
        return followup(sessionId, text, attachments, imagePositions, requirements);
    }

    /** 暂停在当前安全步骤结束后生效，保留任务及排队消息。 */
    default boolean pause(String runId) {
        return false;
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
        return Optional.of("run_" + UUID.randomUUID());
    }

    /** 释放 {@link #claimRun(String)} 占到但没能启动的槽位。 */
    default void abandon(String runId) {
    }

    /** 独占文件恢复/切分支期间的工作区，abandon 时释放；其他会话不能趁此启动。 */
    default boolean lockWorkspace(String operationId, Path workspace) {
        return true;
    }

    /**
     * 谁正占着这个工作区，用于把「工作区仍有任务在运行」变成能直接行动的提示。
     *
     * @return 人类可读的占用方；没有占用或实现方不关心时为空
     */
    default Optional<String> workspaceOccupant(Path workspace) {
        return Optional.empty();
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

    /** 当前模型记忆的上下文占用；token 是保守估算，端点 usage 不适合反推整段记忆。 */
    default ContextStatus contextStatus(String sessionId) {
        return new ContextStatus(0, 0, 1, true, false);
    }

    /** 用户主动压缩已落盘的模型记忆；聊天事件历史不删除。 */
    default CompactionResult compactContext(String sessionId) {
        return new CompactionResult(false, 0, 0, 0, "当前运行器不支持主动压缩");
    }

    record ContextStatus(int messages, int usedTokens, int limitTokens, boolean estimated, boolean canCompact,
        int percent, List<Segment> segments) {
        public ContextStatus(int messages, int usedTokens, int limitTokens, boolean estimated, boolean canCompact) {
            this(messages, usedTokens, limitTokens, estimated, canCompact,
                Math.min(100, (int)Math.round(Math.max(0, usedTokens) * 100.0 / Math.max(1, limitTokens))), List.of());
        }

        /** 兼容旧调用：构成未提供时视为空（前端隐藏构成区）。 */
        public ContextStatus(int messages, int usedTokens, int limitTokens, boolean estimated, boolean canCompact,
            int percent) {
            this(messages, usedTokens, limitTokens, estimated, canCompact, percent, List.of());
        }

        /** 模型记忆按角色的构成（条数与字符数），前端叠加成占比条：看得出记忆里什么在占地方。 */
        public record Segment(String role, int messages, int chars) {
            public Segment {
                role = role == null ? "" : role.strip();
                messages = Math.max(0, messages);
                chars = Math.max(0, chars);
            }
        }

        public ContextStatus {
            messages = Math.max(0, messages);
            usedTokens = Math.max(0, usedTokens);
            limitTokens = Math.max(1, limitTokens);
            percent = Math.max(0, Math.min(100, percent));
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    record CompactionResult(boolean compacted, int coveredMessages, int tokensBefore, int tokensAfter, String message) {
        public CompactionResult {
            coveredMessages = Math.max(0, coveredMessages);
            tokensBefore = Math.max(0, tokensBefore);
            tokensAfter = Math.max(0, tokensAfter);
            message = message == null ? "" : message;
        }
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

    /** 从用户所见队列原子转入当前运行；版本或运行变化时保留原消息。 */
    default boolean steerQueued(String sessionId, String runId, int index, String revision) {
        return false;
    }

    static String queueRevision(List<QueuedMessage> messages) {
        return Sha256.hex(messages.toString());
    }

    /** 最后一项验收完成并释放编辑槽位后，尝试启动等待中的下一件事。 */
    default void requirementsUpdated(String sessionId) {
    }

    /** 等待当前任务结束后执行的用户消息，按入队顺序。 */
    default List<String> queued(String sessionId) {
        return List.of();
    }

    /** 队列是否被用户手动暂停；暂停只阻止「自动开始下一条」，不打断当前运行。 */
    default boolean queueHeld(String sessionId) {
        return false;
    }

    /**
     * 暂停或恢复队列。恢复时若当前没有运行且验收条件已满足，只尝试启动队首一次。
     *
     * @return 是否成功切换（重复暂停/恢复保持幂等）
     */
    default boolean setQueueHeld(String sessionId, boolean held) {
        return false;
    }

    /** 给界面展示的等待原因：手动暂停 / 等待本轮结束 / 等待验收；能立即开始时为空。 */
    default String queueWaitingReason(String sessionId) {
        return "";
    }

    default void discardSteering(String sessionId) {
    }

    /** 改队列里的某一句。下标从 0 开始。 */
    default boolean editQueued(String sessionId, int index, String text) {
        return false;
    }

    default boolean editQueued(String sessionId, int index, String text, String revision) {
        return editQueued(sessionId, index, text, null, revision);
    }

    /** 同时更新正文与这一条自己的约束/验收；requirements 为 null 时保留原要求。 */
    default boolean editQueued(String sessionId, int index, String text, QueueRequirements requirements,
        String revision) {
        return (revision == null || queueRevision(queuedMessages(sessionId)).equals(revision))
            && editQueued(sessionId, index, text);
    }

    default boolean moveQueued(String sessionId, int from, int to, String revision) {
        return (revision == null || queueRevision(queuedMessages(sessionId)).equals(revision))
            && moveQueued(sessionId, from, to);
    }

    default boolean removeQueued(String sessionId, int index, String revision) {
        return (revision == null || queueRevision(queuedMessages(sessionId)).equals(revision))
            && removeQueued(sessionId, index);
    }

    /** 把某一句往前挪一位（和前一句交换）。文字和图片作为一个整体移动。 */
    default boolean moveQueuedUp(String sessionId, int index) {
        return false;
    }

    /** 把某一句挪到指定位置（拖拽排序用），一次完成。文字和图片作为一个整体移动。下标从 0 开始。 */
    default boolean moveQueued(String sessionId, int from, int to) {
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
