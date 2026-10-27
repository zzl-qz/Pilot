package com.pilot.core.multi;

/**
 * 子任务状态机。父 Agent 独占状态推进权，子 Agent 只能上报「完成 / 失败 / 待仲裁」。
 *
 * <pre>
 * ⏳PENDING → 🔄RUNNING → ✅DONE
 *                       ├── ⚠️DISSENT（同任务多方案僵持，等人工仲裁）
 *                       ├── 🔴CONFLICT（沙箱变更合并冲突，等人工裁决）
 *                       ├── ❌FAILED（重试耗尽）
 *                       └── ⛔BLOCKED（上游失败，被阻断）
 * </pre>
 */
public enum TaskStatus {
    PENDING("pending", "⏳等待"),
    RUNNING("running", "🔄运行中"),
    DONE("done", "✅完成"),
    DISSENT("dissent", "⚠️分歧待仲裁"),
    CONFLICT("conflict", "🔴变更冲突"),
    FAILED("failed", "❌失败"),
    BLOCKED("blocked", "⛔已阻断"),
    CANCELLED("cancelled", "🚫已取消");

    private final String wireName;
    private final String label;

    TaskStatus(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    /** 终态不再推进：完成、失败、取消、被阻断。待仲裁与冲突仍需要人/父 Agent 继续处理。 */
    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELLED || this == BLOCKED;
    }

    /** 上游只有「完成」才允许拉起下游；失败、取消、阻断都会传染。 */
    public boolean satisfiesDependency() {
        return this == DONE;
    }

    public static TaskStatus of(String wireName) {
        if (wireName == null) {
            return PENDING;
        }
        for (TaskStatus status : values()) {
            if (status.wireName.equalsIgnoreCase(wireName.strip())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知任务状态: " + wireName);
    }
}
