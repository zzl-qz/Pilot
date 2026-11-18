package com.pilot.core.plan;

import java.util.Set;

/**
 * 计划清单里的一项。
 *
 * <p>除了「做完/没做」，还必须能表达「做不了」和「本轮跳过」：
 * 浏览器回归、外部账号、人工验收这类步骤在当前环境不可执行时，
 * 如果只能选 pending/in_progress/completed，模型要么撒谎标完成，
 * 要么被完成门判成任务失败。{@code blocked/skipped} 就是给这种情况的合法出口，
 * 并强制带一句原因，便于界面和后续轮次直接展示。
 *
 * @param step   步骤描述
 * @param status {@code pending} / {@code in_progress} / {@code completed} / {@code blocked} / {@code skipped}
 * @param note   状态说明；blocked/skipped 时必须非空，其他状态可为空
 */
public record PlanItem(String step, String status, String note) {
    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String BLOCKED = "blocked";
    public static final String SKIPPED = "skipped";

    private static final Set<String> STATUSES = Set.of(PENDING, IN_PROGRESS, COMPLETED, BLOCKED, SKIPPED);

    public PlanItem {
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("step must not be blank");
        }
        status = status == null || status.isBlank() ? PENDING : status.strip();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException(
                "status 必须是 pending / in_progress / completed / blocked / skipped: " + status);
        }
        note = note == null ? "" : note.strip();
        if ((BLOCKED.equals(status) || SKIPPED.equals(status)) && note.isBlank()) {
            throw new IllegalArgumentException("blocked/skipped 必须在 note 中写明原因");
        }
        step = step.strip();
    }

    /** 历史调用方与旧事件只有 step/status。 */
    public PlanItem(String step, String status) {
        this(step, status, "");
    }

    public boolean done() {
        return COMPLETED.equals(status);
    }

    /** 仍需继续执行的步骤；blocked/skipped 不再计入这个集合。 */
    public boolean open() {
        return PENDING.equals(status) || IN_PROGRESS.equals(status);
    }

    public boolean blocked() {
        return BLOCKED.equals(status);
    }

    public boolean skipped() {
        return SKIPPED.equals(status);
    }
}
