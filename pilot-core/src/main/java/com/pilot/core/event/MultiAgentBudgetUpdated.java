package com.pilot.core.event;

/**
 * 单次多智能体派发的预算快照。每次请求完成或预算停止时更新；前端按 dispatchId 合并成一条状态卡片。
 *
 * @param dispatchId 派发编号
 * @param status 当前状态：running、stopped、completed 或 failed，取值见 STATUS_ 常量
 * @param maxDispatchSeconds 墙钟上限（秒）
 * @param maxTotalTokens token 上限；0 表示不限
 * @param usedTokens 已结算的输入与输出 token（含估算）
 * @param estimatedTokens 已结算用量中由本地估算的部分
 * @param reservedTokens 已准入请求的输入 token 预留
 * @param inFlightRequests 当前尚未结算的模型请求数
 * @param stopReason 软停止原因；运行中为空
 * @param cancellationRequested 是否已向在途任务发出中断请求，不代表供应商已撤销请求
 */
public record MultiAgentBudgetUpdated(String dispatchId, String status, int maxDispatchSeconds, long maxTotalTokens,
    long usedTokens, long estimatedTokens, long reservedTokens, int inFlightRequests, String stopReason,
    boolean cancellationRequested) implements SessionEvent {
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public MultiAgentBudgetUpdated {
        dispatchId = dispatchId == null ? "" : dispatchId;
        status = status == null ? STATUS_RUNNING : status;
        maxDispatchSeconds = Math.max(0, maxDispatchSeconds);
        maxTotalTokens = Math.max(0L, maxTotalTokens);
        usedTokens = Math.max(0L, usedTokens);
        estimatedTokens = Math.min(usedTokens, Math.max(0L, estimatedTokens));
        reservedTokens = Math.max(0L, reservedTokens);
        inFlightRequests = Math.max(0, inFlightRequests);
        stopReason = stopReason == null ? "" : stopReason;
    }

    @Override
    public EventType type() {
        return EventType.MULTI_AGENT_BUDGET_UPDATED;
    }
}
