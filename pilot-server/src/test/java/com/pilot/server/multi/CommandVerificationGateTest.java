package com.pilot.server.multi;

import com.pilot.core.exec.*;
import com.pilot.core.multi.*;
import com.pilot.server.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandVerificationGateTest {
    @TempDir Path root;
    private List<SandboxChange> changes() {
        var sandbox = new DiffSandbox("b", "a", "t", "");
        sandbox.add("source", "old", "proposed", true, RiskLevel.LOW); return sandbox.changes();
    }
    private CommandVerificationGate gate(ExecutionBackend backend) {
        var properties = new MultiAgentProperties(true, 2, 1, 10, 10, List.of("project test"));
        var selection = new ExecutionBackends.Selection(backend, ExecutionIsolation.CONTAINER, false, "", ExecutionSettings.local());
        return new CommandVerificationGate(properties, new PilotDataDir(root.toString()), selection);
    }
    @Test void localConfigurationNeverRunsProjectScripts() {
        var backend = mock(ExecutionBackend.class); when(backend.isolation()).thenReturn(ExecutionIsolation.LOCAL);
        assertEquals("skipped", gate(backend).verify(root, "", changes()).status()); verify(backend, never()).run(any());
    }
    @Test void verifiesProposedSnapshotIncludingUntrackedFilesAndCleansUpOnFailure() throws Exception {
        Files.writeString(root.resolve("source"), "old"); Files.writeString(root.resolve("untracked"), "needed");
        var backend = mock(ExecutionBackend.class); when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER);
        when(backend.available()).thenReturn(true); var mount = new AtomicReference<Path>();
        when(backend.run(any())).thenAnswer(call -> {
            ExecutionRequest request = call.getArgument(0); mount.set(request.workspaceRoot());
            assertEquals("proposed", Files.readString(request.workdir().resolve("source")));
            assertEquals("needed", Files.readString(request.workdir().resolve("untracked")));
            return new ExecutionResult(1, "test failed", false, false, "mock", ExecutionIsolation.CONTAINER, "");
        });
        assertEquals("failed", gate(backend).verify(root, "", changes()).status());
        assertFalse(Files.exists(mount.get())); assertEquals("old", Files.readString(root.resolve("source")));
    }
    @Test void sourceMutationCannotProducePassingVerification() throws Exception {
        Files.writeString(root.resolve("source"), "old"); var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER); when(backend.available()).thenReturn(true);
        when(backend.run(any())).thenAnswer(call -> {
            ExecutionRequest request = call.getArgument(0); Files.writeString(request.workdir().resolve("source"), "changed by test");
            return new ExecutionResult(0, "pass", false, false, "mock", ExecutionIsolation.CONTAINER, "");
        });
        var report = gate(backend).verify(root, "", changes()); assertEquals("failed", report.status());
        assertTrue(report.detail().contains("改写了候选")); assertEquals("old", Files.readString(root.resolve("source")));
    }
}
