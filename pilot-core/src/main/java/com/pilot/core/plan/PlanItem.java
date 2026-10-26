package com.pilot.core.plan;

import java.util.Set;

/**
 * 计划清单里的一项。
 *
 * @param step   步骤描述
 * @param status {@code pending} / {@code in_progress} / {@code completed}
 */
public record PlanItem(String step, String status) {
    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";

    private static final Set<String> STATUSES = Set.of(PENDING, IN_PROGRESS, COMPLETED);

    public PlanItem {
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("step must not be blank");
        }
        status = status == null || status.isBlank() ? PENDING : status.strip();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 必须是 pending / in_progress / completed: " + status);
        }
        step = step.strip();
    }

    public boolean done() {
        return COMPLETED.equals(status);
    }
}
