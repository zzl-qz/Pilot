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
    @Test
    void workspaceRestoreBlocksNewRunsAndReleasesLeaseOnAbandon() {
        var registry = new RunRegistry();
        var workspace = java.nio.file.Path.of(".").toAbsolutePath();
        org.junit.jupiter.api.Assertions.assertTrue(registry.claim("run", "one", workspace));
        org.junit.jupiter.api.Assertions.assertFalse(registry.lockWorkspace("restore", workspace));
        registry.unregister("run");
        org.junit.jupiter.api.Assertions.assertTrue(registry.lockWorkspace("restore", workspace));
        org.junit.jupiter.api.Assertions.assertFalse(registry.claim("next", "two", workspace));
        registry.unregister("restore");
        org.junit.jupiter.api.Assertions.assertTrue(registry.claim("next", "two", workspace));
    }

}
