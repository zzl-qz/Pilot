package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.plan.PlanItem;

class UpdatePlanToolTest {
    @Test
    void acceptsBlockedAndSkippedWithReason() {
        List<PlanItem> saved = new ArrayList<>();
        UpdatePlanTool tool = new UpdatePlanTool((items, explanation) -> saved.addAll(items));

        String result = tool.execute(Map.of("plan",
            List.of(Map.of("step", "实现接口", "status", PlanItem.COMPLETED),
                Map.of("step", "浏览器回归", "status", PlanItem.BLOCKED, "note", "环境没有浏览器"),
                Map.of("step", "发布", "status", PlanItem.SKIPPED, "note", "用户同意跳过"))));

        assertTrue(result.contains("1/3"), result);
        assertEquals(PlanItem.BLOCKED, saved.get(1).status());
        assertEquals("环境没有浏览器", saved.get(1).note());
        assertEquals(PlanItem.SKIPPED, saved.get(2).status());
    }

    @Test
    void rejectsBlockedWithoutReason() {
        UpdatePlanTool tool = new UpdatePlanTool((items, explanation) -> {
        });

        String result = tool.execute(Map.of("plan", List.of(Map.of("step", "浏览器回归", "status", PlanItem.BLOCKED))));

        assertTrue(result.contains("note"), result);
    }
}
