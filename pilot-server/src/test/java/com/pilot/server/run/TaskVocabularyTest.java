package com.pilot.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.ToolExecutor;

/** 任务文件和接口里的状态字符串由枚举名决定，这里钉住名字，改名就是改协议。 */
class TaskVocabularyTest {
    @Test
    void taskStatusNamesStayEqualToPersistedStrings() {
        assertEquals(List.of("ACTIVE", "PAUSED", "INTERRUPTED", "AWAITING_REVIEW", "COMPLETED", "FAILED", "BLOCKED",
            "BUDGET_LIMITED", "WAITING", "NEED_INPUT", "UNVERIFIED", "NO_PROGRESS", "REVIEW_UNAVAILABLE",
            "VERIFICATION_FAILED"), names(TaskControlStatus.values()));
    }

    @Test
    void executionModeAndPhaseNamesStayEqualToPersistedStrings() {
        assertEquals(List.of("DIRECT", "GOAL", "UNASSESSED", "PROBE"), names(ExecutionMode.values()));
        assertEquals(
            List.of("EXECUTING", "VERIFYING", "VERIFIED", "UNVERIFIED", "CHECKS_PASSED", "CHECKS_FAILED",
                "CHECKS_STALE", "VERIFICATION_FAILED", "WAIT", "NEED_INPUT", "BLOCKED", "IMPOSSIBLE", "NO_PROGRESS"),
            names(ExecutionPhase.values()));
    }

    @Test
    void directTaskCountsAsFinishedOnlyWithACheckVerdict() {
        assertTrue(ExecutionPhase.directFinished("CHECKS_PASSED"));
        assertTrue(ExecutionPhase.directFinished("CHECKS_FAILED"));
        assertTrue(ExecutionPhase.directFinished("CHECKS_STALE"));
        assertTrue(ExecutionPhase.directFinished("UNVERIFIED"));
        assertFalse(ExecutionPhase.directFinished("EXECUTING"));
        assertFalse(ExecutionPhase.directFinished("VERIFIED"));
        assertFalse(ExecutionPhase.directFinished(null));
    }

    @Test
    void reviewDecisionsKeepSchemaOrderAndStopSet() {
        assertEquals(List.of("COMPLETE", "CONTINUE", "WAIT", "NEED_INPUT", "BLOCKED", "IMPOSSIBLE"),
            ReviewDecision.names());
        assertTrue(ReviewDecision.stops("WAIT"));
        assertTrue(ReviewDecision.stops("NEED_INPUT"));
        assertTrue(ReviewDecision.stops("BLOCKED"));
        assertTrue(ReviewDecision.stops("IMPOSSIBLE"));
        assertFalse(ReviewDecision.stops("COMPLETE"));
        assertFalse(ReviewDecision.stops("CONTINUE"));
        // 停下的结论会原样记为执行阶段，两边必须同名
        ReviewDecision.names().stream().filter(ReviewDecision::stops)
            .forEach(name -> assertEquals(name, ExecutionPhase.valueOf(name).name()));
    }

    @Test
    void evidenceStatusSharesNamesWithToolOutcomeStatus() {
        assertEquals(List.of("SUCCESS", "RUNNING", "PERMANENT_FAILURE", "SKIPPED", "STALE", "STOPPING"),
            names(EvidenceStatus.values()));
        // 证据状态大多直接取工具结果状态的名字，这几个必须对得上
        for (EvidenceStatus shared : List.of(EvidenceStatus.SUCCESS, EvidenceStatus.RUNNING,
            EvidenceStatus.PERMANENT_FAILURE, EvidenceStatus.SKIPPED)) {
            assertEquals(shared.name(), ToolExecutor.OutcomeStatus.valueOf(shared.name()).name());
        }
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
