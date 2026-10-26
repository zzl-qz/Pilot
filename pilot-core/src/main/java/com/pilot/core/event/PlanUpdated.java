package com.pilot.core.event;

import java.util.List;

/**
 * 计划清单更新。事件携带完整列表而不是增量，前端只需整体重绘，避免合并出错。
 *
 * @param plan        全部步骤（含已完成项）
 * @param explanation 模型给出的更新原因，可为空
 */
public record PlanUpdated(List<PlanItemPayload> plan, String explanation) implements SessionEvent {
    public PlanUpdated {
        plan = List.copyOf(plan == null ? List.of() : plan);
        explanation = explanation == null ? "" : explanation;
    }

    @Override
    public EventType type() {
        return EventType.PLAN_UPDATED;
    }

    /** 事件里的计划项，与 core.plan.PlanItem 字段一致，单独定义避免把内部模型暴露给协议层。 */
    public record PlanItemPayload(String step, String status) { }
}
