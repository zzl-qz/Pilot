package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ToolOutcomeUncertaintyTest {
    @Test
    void unknownExternalResultIsNotClassifiedAsRetryableTimeoutOrSuccessfulEvidence() {
        var outcome =
            new ToolExecutor.Outcome("mcp_call", "ERROR: 结果未确认：MCP 发送超时，连接已关闭。外部操作可能已经执行。", (String)null, null);
        assertEquals("RESULT_UNKNOWN", outcome.status().name());
        assertFalse(outcome.usableEvidence());
    }

    @Test
    void ordinaryTimeoutKeepsItsExistingClassification() {
        var outcome = new ToolExecutor.Outcome("web_fetch", "ERROR: 连接超时", (String)null, null);
        assertEquals(ToolExecutor.OutcomeStatus.TRANSIENT_FAILURE, outcome.status());
    }
}
