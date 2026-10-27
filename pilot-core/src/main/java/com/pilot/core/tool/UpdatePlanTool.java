package com.pilot.core.tool;

import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code update_plan}：让模型把多步任务外化成可读的 checklist。
 *
 * <p>参数与 Codex 保持一致（explanation + plan[{step,status}]），减少模型适配成本；
 * 区别是这里通过 {@link PlanSink} 落库并发事件，因为我们要求计划能跨进程恢复。
 *
 * <p>声明为只读：它不碰文件系统，因此任何权限档位都应放行。
 */
public final class UpdatePlanTool implements Tool {
    private final PlanSink sink;

    public UpdatePlanTool(PlanSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
    }

    @Override
    public String name() {
        return "update_plan";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "explanation", Map.of(
                                "type", "string",
                                "description", "本次更新的原因（可选）"),
                        "plan", Map.of(
                                "type", "array",
                                "description", "完整步骤列表，整体替换上一次的计划",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "step", Map.of("type", "string", "description", "步骤描述"),
                                                "status", Map.of(
                                                        "type", "string",
                                                        "enum", List.of(PlanItem.PENDING, PlanItem.IN_PROGRESS,
                                                                PlanItem.COMPLETED),
                                                        "description", "步骤状态")),
                                        "required", List.of("step", "status"),
                                        "additionalProperties", false))),
                "required", List.of("plan"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("plan");
        if (!(raw instanceof List<?> list)) {
            return "ERROR: plan 必须是数组";
        }
        var items = new ArrayList<PlanItem>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                return "ERROR: plan 的每一项必须是对象";
            }
            String step = text(map.get("step"));
            String status = text(map.get("status"));
            if (step.isBlank()) {
                return "ERROR: 每个步骤都需要 step 文本";
            }
            try {
                items.add(new PlanItem(step, status));
            } catch (IllegalArgumentException e) {
                return "ERROR: " + e.getMessage();
            }
        }
        long running = items.stream().filter(item -> PlanItem.IN_PROGRESS.equals(item.status())).count();
        if (running > 1) {
            return "ERROR: 同一时间最多一个步骤处于 in_progress，当前有 " + running + " 个";
        }
        sink.update(List.copyOf(items), text(args == null ? null : args.get("explanation")));
        long done = items.stream().filter(PlanItem::done).count();
        return "Plan updated（" + done + "/" + items.size() + " 已完成）";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
