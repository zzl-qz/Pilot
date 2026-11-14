package com.pilot.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.ExecutionPolicy;

class RunFailureCodeTest {
    /** 客户端按这些字符串区分失败原因，改名等于改协议。 */
    @Test
    void namesStayEqualToWireStrings() {
        assertEquals(
            List.of("CANCELLED", "BUDGET_EXHAUSTED", "CONTEXT_TOO_LARGE", "MODEL_ERROR", "MODEL_INCOMPLETE",
                "MODEL_REPEATED", "OUTPUT_LIMIT", "MAX_STEPS", "TASK_INCOMPLETE", "NO_PROGRESS", "WAITING",
                "NEED_INPUT", "BLOCKED", "BUDGET_LIMITED", "REVIEW_UNAVAILABLE", "VERIFICATION_FAILED", "PAUSED",
                "INTERRUPTED", "INTERNAL_ERROR", "START_FAILED", "QUEUE_START_FAILED"),
            Arrays.stream(RunFailureCode.values()).map(Enum::name).toList());
    }

    @Test
    void eventsAndStopsCarryTheNameAsCode() {
        assertEquals("PAUSED", new RunFailed(RunFailureCode.PAUSED, "已暂停", true).code());
        assertEquals("WAITING", new ExecutionPolicy.Stop(RunFailureCode.WAITING, "等待中").code());
    }
}
