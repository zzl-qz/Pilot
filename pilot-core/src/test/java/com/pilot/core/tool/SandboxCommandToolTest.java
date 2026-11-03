package com.pilot.core.tool;

import com.pilot.core.exec.*;
import com.pilot.core.multi.AgentScope;
import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SandboxCommandToolTest {
    @TempDir Path root;
    private final FakeBackend backend = new FakeBackend();
    @FunctionalInterface interface Action { ExecutionResult run(ExecutionRequest request) throws Exception; }
    private static final class FakeBackend implements ExecutionBackend {
        boolean available = true; int calls; Action action;
        public String id() { return "fake"; }
        public ExecutionIsolation isolation() { return ExecutionIsolation.CONTAINER; }
        public boolean available() { return available; }
        public String unavailableReason() { return available ? "" : "unavailable"; }
        public List<String> describe(ExecutionRequest request) { return List.of(); }
        public ExecutionResult run(ExecutionRequest request) {
            calls++;
            try { return action.run(request); } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    private SandboxCommandTool tool(AgentScope scope, SandboxSink sink) {
        return new SandboxCommandTool(new PathValidator(root, scope), sink,
                new ExecutionBackends.Selection(backend, ExecutionIsolation.CONTAINER, false, "", ExecutionSettings.local()));
    }
    private static SandboxSink proposed(Map<String, String> files) {
        return new SandboxSink() {
            public void record(String path, String oldText, String text) { }
            public Map<String, String> proposedFiles() { return files; }
        };
    }

    @Test void refusesLocalAndUnavailableExecutionWithoutFallback() {
        var local = new SandboxCommandTool(new PathValidator(root), SandboxSink.rejectAll(), ExecutionBackends.select(null));
        assertTrue(local.execute(Map.of("command", "touch should-not-exist")).startsWith("ERROR:"));
        assertFalse(Files.exists(root.resolve("should-not-exist")));
        var isolated = tool(null, proposed(Map.of())); backend.available = false;
        assertTrue(isolated.execute(Map.of("command", "npm test")).startsWith("ERROR:"));
        assertEquals(0, backend.calls);
    }

    @Test void commandSeesOwnAndUpstreamProposalsInDisposableWorkspace() throws Exception {
        Files.writeString(root.resolve("a"), "disk");
        var sink = proposed(Map.of("a", "own")).overlay(proposed(Map.of("a", "upstream", "new", "dependency")));
        var tool = tool(null, sink); var mounted = new AtomicReference<Path>();
        backend.action = request -> { mounted.set(request.workspaceRoot());
            assertNotEquals(root.toRealPath(), request.workspaceRoot());
            assertEquals("own", Files.readString(request.workdir().resolve("a")));
            assertEquals("dependency", Files.readString(request.workdir().resolve("new")));
            return new ExecutionResult(0, "passed", false, false, "fake", ExecutionIsolation.CONTAINER, "");
        };
        assertTrue(tool.execute(Map.of("command", "npm test")).contains("退出码: 0"));
        assertFalse(Files.exists(mounted.get())); assertEquals("disk", Files.readString(root.resolve("a")));
    }

    @Test void validatesArgumentsAndPreservesPermissionGate() {
        var tool = tool(AgentScope.readWrite(List.of(), List.of()).withCommands(List.of("npm test"), List.of()), proposed(Map.of()));
        for (var args : List.of(Map.of("command", "npm test", "timeoutSeconds", 0), Map.of("command", "npm test", "timeoutSeconds", 1.5),
                Map.of("command", "npm test", "workdir", "../outside"), Map.of("command", "npm publish"), Map.of("command", "node -e evil")))
            assertTrue(tool.execute(new HashMap<>(args)).startsWith("ERROR:"), args.toString());
        assertFalse(tool.readOnly(Map.of("command", "npm test")));
        assertTrue(tool.confirm(Map.of("command", "git push")));
        assertEquals(0, backend.calls);
    }

    @Test void mutatedCandidatesAndTimeoutsNeverCountAsPassingChecks() throws Exception {
        Files.writeString(root.resolve("a"), "old"); var tool = tool(null, proposed(Map.of()));
        backend.action = request -> { Files.writeString(request.workdir().resolve("a"), "mutated");
            return new ExecutionResult(0, "passed", false, false, "fake", ExecutionIsolation.CONTAINER, "");
        };
        assertTrue(tool.execute(Map.of("command", "npm test")).contains("改写了候选"));
        assertEquals("old", Files.readString(root.resolve("a")));
        backend.action = request -> new ExecutionResult(0, "partial", true, false, "fake", ExecutionIsolation.CONTAINER, "");
        assertTrue(tool.execute(Map.of("command", "npm test")).contains("超时"));
    }
}
