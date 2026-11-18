package com.pilot.core.event;

import java.util.Locale;

/**
 * 一次运行的终态语义。
 *
 * <p>它回答的是「这一轮到底算完成、部分交付、被阻塞，还是出错」，而不是
 * 「循环为什么停下来」。{@link RunFailureCode} 仍然描述停止原因，
 * 但只有 {@link #FAILED} 才应该被界面和指标当成失败。
 *
 * <p>这样拆开之后，「计划没做完」「等用户补充」「环境阻塞」不会再和
 * 模型接口 401、进程异常混成同一个红色失败。
 */
public enum RunOutcome {
    /** 目标完成，计划与验证证据齐全。 */
    COMPLETED,
    /** 有交付，但仍有未完成/未验证/被阻塞的步骤。 */
    PARTIAL,
    /** 环境、权限、依赖或外部条件阻塞，需要人处理。 */
    BLOCKED,
    /** 缺少用户信息。 */
    NEED_INPUT,
    /** 等待外部事件（后台任务、预约、审批等）。 */
    WAITING,
    /** 用户或系统暂停。 */
    PAUSED,
    /** 用户取消。 */
    CANCELLED,
    /** 预算用尽。 */
    BUDGET_LIMITED,
    /** 多轮没有有效进展。 */
    NO_PROGRESS,
    /** 核验器不可用。 */
    REVIEW_UNAVAILABLE,
    /** 验证策略未通过。 */
    VERIFICATION_FAILED,
    /** 真正的失败：模型接口、持久化、未捕获异常等基础设施问题。 */
    FAILED;

    /** 从历史失败码/任务状态反推终态，保证旧事件也能读。 */
    public static RunOutcome fromCode(String code) {
        if (code == null || code.isBlank()) {
            return COMPLETED;
        }
        return switch (code.strip().toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> COMPLETED;
            case "TASK_INCOMPLETE", "PARTIAL" -> PARTIAL;
            case "BLOCKED" -> BLOCKED;
            case "NEED_INPUT" -> NEED_INPUT;
            case "WAITING" -> WAITING;
            case "PAUSED" -> PAUSED;
            case "CANCELLED" -> CANCELLED;
            case "BUDGET_LIMITED", "BUDGET_EXHAUSTED" -> BUDGET_LIMITED;
            case "NO_PROGRESS" -> NO_PROGRESS;
            case "REVIEW_UNAVAILABLE" -> REVIEW_UNAVAILABLE;
            case "VERIFICATION_FAILED" -> VERIFICATION_FAILED;
            default -> FAILED;
        };
    }

    /** 只有基础设施错误才是失败；其余都是「还需要继续/处理」。 */
    public boolean failure() {
        return this == FAILED;
    }

    /** 是否可以在同一会话继续。 */
    public boolean resumable() {
        return this != COMPLETED && this != FAILED;
    }

    /** 对应的任务合同状态名，服务端直接落库，不再各层自己映射。 */
    public String taskStatus() {
        return switch (this) {
            case COMPLETED -> "COMPLETED";
            case PARTIAL -> "AWAITING_REVIEW";
            case FAILED -> "FAILED";
            default -> name();
        };
    }

    /** 对应的运行检查点状态：只有 COMPLETED / PAUSED / FAILED 是终态，其余可恢复。 */
    public String checkpointStatus() {
        return switch (this) {
            case COMPLETED -> "COMPLETED";
            case PAUSED -> "PAUSED";
            case FAILED -> "FAILED";
            default -> "INTERRUPTED";
        };
    }

    /** runs 表里的状态：部分交付记成 INTERRUPTED，避免被统计成失败。 */
    public String runStatus() {
        return switch (this) {
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            default -> "INTERRUPTED";
        };
    }
}
