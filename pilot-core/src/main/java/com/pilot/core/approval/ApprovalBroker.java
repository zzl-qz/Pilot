package com.pilot.core.approval;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 审批挂起/恢复的注册表：Agent 线程在 {@link #register} 返回的 Future 上阻塞，
 * REST 审批接口通过 {@link #resolve} 唤醒它。超时由具体实现负责以 {@code false} 结束。
 */
public interface ApprovalBroker {
    /**
     * 注册一次待审批调用。返回的 Future 在批准时为 {@code true}，拒绝或超时为 {@code false}。
     * 同一个 callId 只能注册一次，避免批准落到错误的调用上。
     *
     * @param args 结构化参数（仅短标量值），供前端展示与「批准并信任」等次级决策
     */
    CompletableFuture<Boolean> register(String callId, String toolName, String argsSummary, Map<String, Object> args);

    default CompletableFuture<Boolean> register(String sessionId, String callId, String toolName, String argsSummary,
        Map<String, Object> args) {
        return register(callId, toolName, argsSummary, args);
    }

    /** @return 是否成功唤醒了一个等待中的审批（找不到或已超时返回 false） */
    default boolean resolve(String callId, boolean approved) {
        return false;
    }

    /** 带用户说明的审批决策：拒绝时附带原因供前端回放与模型调整方案。 */
    default boolean resolve(String callId, boolean approved, String reason) {
        return resolve(callId, approved);
    }

    /** 取出并清理用户拒绝时填写的理由（一次性读取，避免残留）。 */
    default String takeRejectionReason(String callId) {
        return "";
    }

    default List<PendingApproval> pending() {
        return List.of();
    }

    /** 为特定会话绑定所属 sessionId 的轻量包装器。 */
    static ApprovalBroker forSession(ApprovalBroker delegate, String sessionId) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        String cleanSessionId = sessionId == null ? "" : sessionId.strip();
        if (cleanSessionId.isEmpty()) {
            return delegate;
        }
        return new ApprovalBroker() {
            @Override
            public CompletableFuture<Boolean> register(String callId, String toolName, String argsSummary,
                Map<String, Object> args) {
                return delegate.register(cleanSessionId, callId, toolName, argsSummary, args);
            }

            @Override
            public CompletableFuture<Boolean> register(String explicitSessionId, String callId, String toolName,
                String argsSummary, Map<String, Object> args) {
                String owner = explicitSessionId == null || explicitSessionId.isBlank()
                    ? cleanSessionId : explicitSessionId.strip();
                return delegate.register(owner, callId, toolName, argsSummary, args);
            }

            @Override
            public boolean resolve(String callId, boolean approved) {
                return delegate.resolve(callId, approved);
            }

            @Override
            public boolean resolve(String callId, boolean approved, String reason) {
                return delegate.resolve(callId, approved, reason);
            }

            @Override
            public String takeRejectionReason(String callId) {
                return delegate.takeRejectionReason(callId);
            }

            @Override
            public List<PendingApproval> pending() {
                return delegate.pending();
            }
        };
    }

    /** 无审批能力的默认实现：任何需要审批的调用都直接视为拒绝。 */
    static ApprovalBroker denyAll() {
        return (callId, toolName, argsSummary, args) -> CompletableFuture.completedFuture(false);
    }
}
