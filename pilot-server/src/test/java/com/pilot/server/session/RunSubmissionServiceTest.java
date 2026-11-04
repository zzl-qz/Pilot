package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.event.RunFailed;
import com.pilot.core.session.SessionService;

class RunSubmissionServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesStartupFailureWhenPublishingFailureAlsoFails(boolean sameFailure) {
        SessionService sessions = mock(SessionService.class);
        RunStarter runs = mock(RunStarter.class);
        RuntimeException startupFailure = new IllegalStateException("cannot start");
        RuntimeException reportingFailure = sameFailure ? startupFailure : new IllegalStateException("cannot report");
        doThrow(startupFailure).when(runs).resume("session", "run");
        doThrow(reportingFailure).when(sessions).publish(eq("session"), eq("run"), any(RunFailed.class));

        RuntimeException actual =
            assertThrows(RuntimeException.class, () -> RunSubmissionService.resume(sessions, runs, "session", "run"));

        assertSame(startupFailure, actual);
        assertArrayEquals(sameFailure ? new Throwable[0] : new Throwable[] {reportingFailure}, actual.getSuppressed());
        verify(runs).abandon("run");
    }
}
