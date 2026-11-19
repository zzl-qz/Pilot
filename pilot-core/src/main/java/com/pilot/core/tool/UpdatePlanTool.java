package com.pilot.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.pilot.core.message.ToolNames;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.plan.PlanSink;

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
    private final Supplier<List<PlanItem>> current;

    public UpdatePlanTool(PlanSink sink) {
        this(sink, List::of);
    }

    /** current 提供这次更新之前已经落库的计划，用来保住已完成步骤。 */
    public UpdatePlanTool(PlanSink sink, Supplier<List<PlanItem>> current) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        this.current = current == null ? List::of : current;
    }

    @Override
    public String name() {
        return ToolNames.UPDATE_PLAN;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("explanation", ToolSchemas.string("本次更新的原因（可选）"), "plan",
                Map.of("type", "array", "description", "完整步骤列表，整体替换上一次的计划；大型任务按 5–12 个可验证步骤拆分", "items",
                    ToolSchemas.object(Map.of("step", ToolSchemas.string("步骤描述"), "status",
                        Map.of("type", "string", "enum",
                            List.of(PlanItem.PENDING, PlanItem.IN_PROGRESS, PlanItem.COMPLETED, PlanItem.BLOCKED,
                                PlanItem.SKIPPED),
                            "description", "步骤状态；blocked/skipped 必须同时在 note 写原因"),
                        "note", ToolSchemas.string("可选说明；blocked/skipped 时必填原因")), List.of("step", "status")))),
            List.of("plan"));
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
        ArrayList<PlanItem> items = new ArrayList<PlanItem>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                return "ERROR: plan 的每一项必须是对象";
            }
            String step = text(map.get("step"));
            String status = text(map.get("status"));
            String note = text(map.get("note"));
            if (step.isBlank()) {
                return "ERROR: 每个步骤都需要 step 文本";
            }
            try {
                items.add(new PlanItem(step, status, note));
            } catch (IllegalArgumentException e) {
                return "ERROR: " + e.getMessage();
            }
        }
        items = keepCompleted(items);
        long running = items.stream().filter(item -> PlanItem.IN_PROGRESS.equals(item.status())).count();
        if (running > 1) {
            return "ERROR: 同一时间最多一个步骤处于 in_progress，当前有 " + running + " 个";
        }
        sink.update(List.copyOf(items), text(args == null ? null : args.get("explanation")));
        long done = items.stream().filter(PlanItem::done).count();
        // 把完整计划回给模型，作为后续步骤的唯一可见副本；不再依赖每轮重写 system 的 planBrief。
        StringBuilder detail =
            new StringBuilder("Plan updated（").append(done).append("/").append(items.size()).append(" 已完成）\n");
        for (PlanItem item : items) {
            detail.append("- [").append(item.status()).append("] ").append(item.step());
            if (!item.note().isBlank()) {
                detail.append("（").append(item.note()).append("）");
            }
            detail.append('\n');
        }
        return detail.toString();
    }

    /** 已完成的步骤即使这次换了说法被漏掉，也留在清单前面，不能退回未做。 */
    private ArrayList<PlanItem> keepCompleted(ArrayList<PlanItem> incoming) {
        List<PlanItem> previous = current.get();
        if (previous == null || previous.isEmpty()) {
            return incoming;
        }
        LinkedHashMap<String, PlanItem> kept = new LinkedHashMap<>();
        for (PlanItem old : previous) {
            if (old.done()) {
                kept.put(old.step(), new PlanItem(old.step(), PlanItem.COMPLETED, old.note()));
            }
        }
        ArrayList<PlanItem> merged = new ArrayList<>();
        for (PlanItem item : incoming) {
            PlanItem done = kept.remove(item.step());
            merged.add(done == null ? item : done);
        }
        merged.addAll(0, kept.values());
        return merged;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
