package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RunRegistryTest {
    @org.junit.jupiter.api.io.TempDir
    Path root;

    @Test
    void restoreAndRunsAreMutuallyExclusiveAcrossNestedWorkspaces() throws Exception {
        RunRegistry registry = new RunRegistry();
        Path parent = Files.createDirectories(root.resolve("repo"));
        Path child = Files.createDirectories(parent.resolve("child"));
        Path sibling = Files.createDirectories(root.resolve("repo-other"));
        Assertions.assertTrue(registry.claim("child-run", "one", child));
        Assertions.assertFalse(registry.lockWorkspace("restore", parent));
        registry.unregister("child-run");
        Assertions.assertTrue(registry.lockWorkspace("restore", parent));
        Assertions.assertFalse(registry.claim("next", "two", child));
        Assertions.assertThrows(IllegalStateException.class, () -> registry.register("direct", "three", child));
        Assertions.assertFalse(registry.lockWorkspace("child-restore", child));
        Assertions.assertTrue(registry.claim("sibling-run", "four", sibling));
        registry.unregister("restore");
        Assertions.assertTrue(registry.claim("next", "two", child));
    }

    @Test
    void unregisteringPreviousRunDoesNotClearAutomaticallyContinuedRun() {
        RunRegistry registry = new RunRegistry();
        registry.register("run-old", "session");
        registry.register("run-next", "session");

        registry.unregister("run-old");

        assertEquals("run-next", registry.activeRun("session").orElseThrow());
    }

    @Test
    void workspaceRestoreBlocksNewRunsAndReleasesLeaseOnAbandon() {
        RunRegistry registry = new RunRegistry();
        Path workspace = Path.of(".").toAbsolutePath();
        Assertions.assertTrue(registry.claim("run", "one", workspace));
        Assertions.assertFalse(registry.lockWorkspace("restore", workspace));
        registry.unregister("run");
        Assertions.assertTrue(registry.lockWorkspace("restore", workspace));
        Assertions.assertFalse(registry.claim("next", "two", workspace));
        registry.unregister("restore");
        Assertions.assertTrue(registry.claim("next", "two", workspace));
    }

}
