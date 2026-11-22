package com.pilot.server.run;

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

    /** 409 要能说清是谁占着工作区，不然用户只能挨个会话翻。 */
    @Test
    void occupierNamesTheRunHoldingTheWorkspace() throws Exception {
        RunRegistry registry = new RunRegistry();
        Path workspace = Files.createDirectories(root.resolve("repo"));
        Assertions.assertTrue(registry.claim("run-1", "session-1", workspace));

        RunRegistry.Occupant occupier = registry.occupier(workspace).orElseThrow();

        assertEquals("run-1", occupier.runId());
        assertEquals("session-1", occupier.sessionId());
        registry.unregister("run-1");
        Assertions.assertTrue(registry.occupier(workspace).isEmpty());
    }

}
