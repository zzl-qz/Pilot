package com.pilot.core.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanItemTest {
    @Test
    void blockedAndSkippedAreClosedButRequireReason() {
        PlanItem blocked = new PlanItem("浏览器回归", PlanItem.BLOCKED, "环境没有浏览器");
        PlanItem skipped = new PlanItem("发布", PlanItem.SKIPPED, "用户同意跳过");

        assertTrue(blocked.blocked());
        assertFalse(blocked.open());
        assertFalse(blocked.done());
        assertTrue(skipped.skipped());
        assertFalse(skipped.open());

        assertThrows(IllegalArgumentException.class, () -> new PlanItem("浏览器回归", PlanItem.BLOCKED));
        assertThrows(IllegalArgumentException.class, () -> new PlanItem("发布", PlanItem.SKIPPED, " "));
    }

    @Test
    void legacyConstructorStillWorks() {
        PlanItem item = new PlanItem("实现", PlanItem.IN_PROGRESS);
        assertTrue(item.open());
        assertFalse(item.done());
    }
}
