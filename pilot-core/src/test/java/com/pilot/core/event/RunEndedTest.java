package com.pilot.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.plan.PlanItem;

class RunEndedTest {
    @Test
    void partialOutcomeCarriesPlanAndArtifacts() {
        List<PlanItem> plan = List.of(new PlanItem("实现", PlanItem.COMPLETED), new PlanItem("验证", PlanItem.IN_PROGRESS),
            new PlanItem("浏览器回归", PlanItem.BLOCKED, "没有浏览器"), new PlanItem("文档", PlanItem.SKIPPED, "用户同意跳过"));

        RunEnded ended = RunEnded.of(RunOutcome.PARTIAL, "TASK_INCOMPLETE", "部分交付", List.of("src/A.java"),
            List.of("npm test → exit 0"), plan, List.of("继续执行"));

        assertEquals(RunOutcome.PARTIAL, ended.outcome());
        assertEquals("TASK_INCOMPLETE", ended.code());
        assertEquals(1, ended.planDone());
        assertEquals(4, ended.planTotal());
        assertEquals(List.of("验证"), ended.openSteps());
        assertEquals(List.of("浏览器回归"), ended.blockedSteps());
        assertEquals(List.of("src/A.java"), ended.changedFiles());
        assertEquals(List.of("npm test → exit 0"), ended.checks());
        assertTrue(ended.resumable());
        assertFalse(ended.outcome().failure());
    }

    @Test
    void taskAndCheckpointStatusesComeFromOutcome() {
        assertEquals("AWAITING_REVIEW", RunOutcome.PARTIAL.taskStatus());
        assertEquals("INTERRUPTED", RunOutcome.PARTIAL.checkpointStatus());
        assertEquals("INTERRUPTED", RunOutcome.PARTIAL.runStatus());

        assertEquals("BLOCKED", RunOutcome.BLOCKED.taskStatus());
        assertEquals("INTERRUPTED", RunOutcome.BLOCKED.checkpointStatus());

        assertEquals("FAILED", RunOutcome.FAILED.taskStatus());
        assertEquals("FAILED", RunOutcome.FAILED.checkpointStatus());
        assertEquals("FAILED", RunOutcome.FAILED.runStatus());
        assertTrue(RunOutcome.FAILED.failure());
    }

    @Test
    void legacyCodesMapToNonFailureWhereAppropriate() {
        assertEquals(RunOutcome.PARTIAL, RunOutcome.fromCode("TASK_INCOMPLETE"));
        assertEquals(RunOutcome.NEED_INPUT, RunOutcome.fromCode("NEED_INPUT"));
        assertEquals(RunOutcome.BUDGET_LIMITED, RunOutcome.fromCode("BUDGET_EXHAUSTED"));
        assertEquals(RunOutcome.FAILED, RunOutcome.fromCode("MODEL_ERROR"));
        assertEquals(RunOutcome.COMPLETED, RunOutcome.fromCode(null));
    }
}
