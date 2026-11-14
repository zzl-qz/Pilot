package com.pilot.server.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 枚举名就是落库和接口里的字符串；这里把它们钉死，改名会直接失败。 */
class ScheduleVocabularyTest {
    @Test
    void enumNamesStayEqualToPersistedStrings() {
        assertEquals(List.of("SCHEDULED", "DISPATCHING", "RUNNING", "WAITING", "PAUSED", "COMPLETED", "CANCELLED",
            "NEEDS_ATTENTION"), names(ScheduleState.values()));
        assertEquals(List.of("ACTIVE", "PAUSED", "CANCELLED", "ERROR"), names(SeriesState.values()));
        assertEquals(List.of("ONCE", "SKIP"), names(MisfirePolicy.values()));
        assertEquals(List.of("ISOLATED", "SHARED"), names(WorkspaceMode.values()));
    }

    @Test
    void unknownStoredStatesAreNeitherTerminalNorDispatched() {
        assertTrue(ScheduleState.terminal("COMPLETED"));
        assertTrue(ScheduleState.terminal("CANCELLED"));
        assertTrue(ScheduleState.terminal("NEEDS_ATTENTION"));
        assertFalse(ScheduleState.terminal("PAUSED"));
        assertFalse(ScheduleState.terminal("旧版本状态"));
        assertFalse(ScheduleState.terminal(null));
        assertTrue(ScheduleState.dispatched("RUNNING"));
        assertTrue(ScheduleState.dispatched("DISPATCHING"));
        assertFalse(ScheduleState.dispatched("WAITING"));
        assertFalse(ScheduleState.dispatched(null));
    }

    @Test
    void actionsMatchLowercaseWordsExactly() {
        assertEquals(ScheduleAction.CANCEL, ScheduleAction.parse("cancel"));
        assertEquals(ScheduleAction.PAUSE, ScheduleAction.parse("pause"));
        assertEquals(ScheduleAction.RESUME, ScheduleAction.parse("resume"));
        assertEquals(ScheduleAction.RESCHEDULE, ScheduleAction.parse("reschedule"));
        for (String rejected : new String[] {"Cancel", "PAUSE", " resume", "", null}) {
            assertEquals("不支持的操作",
                assertThrows(IllegalArgumentException.class, () -> ScheduleAction.parse(rejected)).getMessage());
        }
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
