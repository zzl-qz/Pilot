package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ForegroundPaceTest {
    @Test
    void buildAndTestCommandsAreAllowedToStayQuiet() {
        assertTrue(ForegroundPace.expectsLong("mvn -q test"));
        assertTrue(ForegroundPace.expectsLong("npm test"));
        assertTrue(ForegroundPace.expectsLong("npm run test:composer"));
        assertTrue(ForegroundPace.expectsLong("npx playwright test"));
        assertTrue(ForegroundPace.expectsLong("vitest run"));
        assertTrue(ForegroundPace.expectsLong("go test ./..."));
        assertFalse(ForegroundPace.expectsLong("mvn -v"));
        assertFalse(ForegroundPace.expectsLong("git status"));
    }

    @Test
    void silenceReleasesOrdinaryCommandsButNotLongOnes() {
        assertEquals(ForegroundPace.Release.SILENT, ForegroundPace.release(false, 30_000, 30_000, 30_000, 120_000));
        assertEquals(ForegroundPace.Release.NONE, ForegroundPace.release(true, 30_000, 30_000, 30_000, 120_000));
        assertEquals(ForegroundPace.Release.STILL_WORKING,
            ForegroundPace.release(true, 1_000, 120_000, 30_000, 120_000));
    }
}
