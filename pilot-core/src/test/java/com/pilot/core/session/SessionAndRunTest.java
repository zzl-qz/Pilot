package com.pilot.core.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionAndRunTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void sessionNormalizesTitleAndWorkspace() {
        Session session = new Session("ses_1", "  Task  ", NOW, SessionStatus.CREATED, "  C:/repo  ");
        assertEquals("Task", session.title());
        assertEquals("C:/repo", session.workspace());
    }

    @Test
    void sessionRejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new Session(" ", "Task", NOW, SessionStatus.CREATED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Session("ses_1", " ", NOW, SessionStatus.CREATED, null));
    }

    @Test
    void runRejectsMissingIdentifiersAndTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> new Run(" ", "ses_1", NOW));
        assertThrows(IllegalArgumentException.class, () -> new Run("run_1", " ", NOW));
        assertThrows(NullPointerException.class, () -> new Run("run_1", "ses_1", null));
    }
}
