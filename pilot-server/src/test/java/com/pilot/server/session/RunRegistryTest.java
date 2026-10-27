package com.pilot.server.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunRegistryTest {

    @Test
    void unregisteringPreviousRunDoesNotClearAutomaticallyContinuedRun() {
        var registry = new RunRegistry();
        registry.register("run-old", "session");
        registry.register("run-next", "session");

        registry.unregister("run-old");

        assertEquals("run-next", registry.activeRun("session").orElseThrow());
    }
}
