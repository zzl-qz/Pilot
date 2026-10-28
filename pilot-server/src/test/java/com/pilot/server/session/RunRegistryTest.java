package com.pilot.server.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunRegistryTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

    @Test
    void restoreAndRunsAreMutuallyExclusiveAcrossNestedWorkspaces() throws Exception {
        var registry = new RunRegistry();
        var parent = java.nio.file.Files.createDirectories(root.resolve("repo"));
        var child = java.nio.file.Files.createDirectories(parent.resolve("child"));
        var sibling = java.nio.file.Files.createDirectories(root.resolve("repo-other"));
        org.junit.jupiter.api.Assertions.assertTrue(registry.claim("child-run", "one", child));
        org.junit.jupiter.api.Assertions.assertFalse(registry.lockWorkspace("restore", parent));
        registry.unregister("child-run");
        org.junit.jupiter.api.Assertions.assertTrue(registry.lockWorkspace("restore", parent));
        org.junit.jupiter.api.Assertions.assertFalse(registry.claim("next", "two", child));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> registry.register("direct", "three", child));
        org.junit.jupiter.api.Assertions.assertFalse(registry.lockWorkspace("child-restore", child));
        org.junit.jupiter.api.Assertions.assertTrue(registry.claim("sibling-run", "four", sibling));
        registry.unregister("restore");
        org.junit.jupiter.api.Assertions.assertTrue(registry.claim("next", "two", child));
    }

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
