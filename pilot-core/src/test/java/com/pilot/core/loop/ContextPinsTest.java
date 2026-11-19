package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContextPinsTest {
    @Test
    void keepsTheGoalWhenReadListIsLonger() {
        String goal = "用户原始目标：保留支付开关";
        String reads = "已读文件".repeat(400);
        String pins = "\n<pilot_context_metadata>{\"schema_version\":1,\"source_sha256\":\"abc\","
            + "\"covered_messages\":2,\"evidence_refs\":[\"" + reads + "\"],\"user_requests\":[\"" + goal
            + "\"]}</pilot_context_metadata>";

        String fitted = ContextPins.limit(pins, 500);

        assertTrue(fitted.contains(goal), fitted);
        assertFalse(fitted.contains(reads));
        assertTrue(fitted.length() <= 500, String.valueOf(fitted.length()));
    }

    @Test
    void unparsedPinsKeepTheTailGoal() {
        String goal = "用户原始目标：保留支付开关";
        String pins = "已读文件".repeat(400) + goal;

        String fitted = ContextPins.limit(pins, 80);

        assertTrue(fitted.contains(goal), fitted);
        assertTrue(fitted.length() <= 80, String.valueOf(fitted.length()));
    }
}
