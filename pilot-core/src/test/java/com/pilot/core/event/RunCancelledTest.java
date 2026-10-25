package com.pilot.core.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunCancelledTest {
    @Test
    void exposesVersionedWireEventTypeAndNormalizedReason() {
        RunCancelled event = new RunCancelled("  用户取消  ");

        assertEquals(EventType.RUN_CANCELLED, event.type());
        assertEquals("run.cancelled", event.type().wireName());
        assertEquals("用户取消", event.reason());
    }

    @Test
    void rejectsBlankReason() {
        assertThrows(IllegalArgumentException.class, () -> new RunCancelled(" "));
    }
}
