package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class CommandTaskStatusTest {
    /** 这些名字写在任务状态文件和接口里，改名等于改协议。 */
    @Test
    void namesStayEqualToPersistedStrings() {
        assertEquals(List.of("RUNNING", "STOPPING", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED",
            "OUTPUT_LIMIT"), Arrays.stream(CommandTaskStatus.values()).map(Enum::name).toList());
    }

    @Test
    void onlyRunningAndStoppingCountAsActive() {
        assertTrue(CommandTaskStatus.active("RUNNING"));
        assertTrue(CommandTaskStatus.active("STOPPING"));
        assertFalse(CommandTaskStatus.active("COMPLETED"));
        assertFalse(CommandTaskStatus.active("running"));
        assertFalse(CommandTaskStatus.active(null));
    }
}
