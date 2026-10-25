package com.pilot.core.approval;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 审批挂起/恢复的注册表：Agent 线程在 {@link #register} 返回的 Future 上阻塞，
 * REST 审批接口通过 {@link #resolve} 唤醒它。超时由具体实现负责以 {@code false} 结束。
 */
public interface ApprovalBroker {
    /**
     * 注册一次待审批调用。返回的 Future 在批准时为 {@code true}，拒绝或超时为 {@code false}。
     * 同一个 callId 只能注册一次，避免批准落到错误的调用上。
     */
    CompletableFuture<Boolean> register(String callId, String toolName, String argsSummary);

    /** @return 是否成功唤醒了一个等待中的审批（找不到或已超时返回 false） */
    default boolean resolve(String callId, boolean approved) {
        return false;
    }

    default List<PendingApproval> pending() {
        return List.of();
    }

    /** 无审批能力的默认实现：任何需要审批的调用都直接视为拒绝。 */
    static ApprovalBroker denyAll() {
        return (callId, toolName, argsSummary) -> CompletableFuture.completedFuture(false);
    }
}
