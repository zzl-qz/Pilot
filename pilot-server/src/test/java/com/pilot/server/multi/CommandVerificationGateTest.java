package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.exec.ExecutionBackend;
import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.exec.ExecutionIsolation;
import com.pilot.core.exec.ExecutionRequest;
import com.pilot.core.exec.ExecutionResult;
import com.pilot.core.exec.ExecutionSettings;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.core.multi.SandboxChange;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.MultiAgentProperties;

class CommandVerificationGateTest {
    @TempDir
    Path root;

    private List<SandboxChange> changes() {
        var sandbox = new DiffSandbox("b", "a", "t", "");
        sandbox.add("source", "old", "proposed", true, RiskLevel.LOW);
        return sandbox.changes();
    }

    private CommandVerificationGate gate(ExecutionBackend backend) {
        var properties = new MultiAgentProperties(true, 2, 1, 10, 10, List.of("project test"));
        var selection = new ExecutionBackends.Selection(backend, ExecutionIsolation.CONTAINER, false, "",
            ExecutionSettings.local());
        return new CommandVerificationGate(properties, new PilotDataDir(root.toString()), selection);
    }

    @Test
    void localConfigurationNeverRunsProjectScripts() {
        var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.LOCAL);
        assertEquals("skipped", gate(backend).verify(root, "", changes()).status());
        verify(backend, never()).run(any());
    }

    @Test
    void verifiesProposedSnapshotIncludingUntrackedFilesAndCleansUpOnFailure() throws Exception {
        Files.writeString(root.resolve("source"), "old");
        Files.writeString(root.resolve("untracked"), "needed");
        var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER);
        when(backend.available()).thenReturn(true);
        var mount = new AtomicReference<Path>();
        when(backend.run(any())).thenAnswer(call -> {
            ExecutionRequest request = call.getArgument(0);
            mount.set(request.workspaceRoot());
            assertEquals("proposed", Files.readString(request.workdir().resolve("source")));
            assertEquals("needed", Files.readString(request.workdir().resolve("untracked")));
            return new ExecutionResult(1, "test failed", false, false, "mock", ExecutionIsolation.CONTAINER, "");
        });
        assertEquals("failed", gate(backend).verify(root, "", changes()).status());
        assertFalse(Files.exists(mount.get()));
        assertEquals("old", Files.readString(root.resolve("source")));
    }

    @Test
    void sourceMutationCannotProducePassingVerification() throws Exception {
        Files.writeString(root.resolve("source"), "old");
        var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER);
        when(backend.available()).thenReturn(true);
        when(backend.run(any())).thenAnswer(call -> {
            ExecutionRequest request = call.getArgument(0);
            Files.writeString(request.workdir().resolve("source"), "changed by test");
            return new ExecutionResult(0, "pass", false, false, "mock", ExecutionIsolation.CONTAINER, "");
        });
        var report = gate(backend).verify(root, "", changes());
        assertEquals("failed", report.status());
        assertTrue(report.detail().contains("改写了候选"));
        assertEquals("old", Files.readString(root.resolve("source")));
    }

    @Test
    void usesCandidateManifestAndRunsInstallBuildAndTestInOrder() throws Exception {
        Files.writeString(root.resolve("source"), "old");
        var box = new DiffSandbox("b", "a", "t", "");
        box.add("package.json", null, "{\"scripts\":{\"build\":\"node build.mjs\",\"test\":\"node --test\"}}", false,
            RiskLevel.LOW);
        box.add("package-lock.json", null, "{}", false, RiskLevel.LOW);
        var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER);
        when(backend.available()).thenReturn(true);
        List<String> calls = new ArrayList<>();
        when(backend.run(any())).thenAnswer(call -> {
            ExecutionRequest request = call.getArgument(0);
            calls.add(request.script());
            assertFalse(request.network());
            assertNotNull(request.dependencyCache());
            return new ExecutionResult(0, "ok", false, false, "mock", ExecutionIsolation.CONTAINER, "");
        });
        var selection = new ExecutionBackends.Selection(backend, ExecutionIsolation.CONTAINER, false, "",
            ExecutionSettings.local());
        var gate = new CommandVerificationGate(new MultiAgentProperties(true, 2, 1, 10, 10, List.of()),
            new PilotDataDir(root.resolve(".pilot").toString()), selection);
        assertTrue(gate.verify(root, "", box.changes()).ok());
        assertEquals(4, calls.size());
        assertTrue(calls.get(0).contains("command -v node"));
        assertEquals("npm ci --no-audit --no-fund --offline", calls.get(1));
        assertEquals("npm run build --silent", calls.get(2));
        assertEquals("npm test --silent", calls.get(3));
        Files.writeString(root.resolve("package.json"),
            "{\"dependencies\":{\"x\":\"1\"},\"scripts\":{\"test\":\"node --test\"}}");
        assertTrue(gate.verify(root, "", changes()).detail().contains("缺少锁文件"));
        assertEquals(4, calls.size(), "缺少锁文件时不能执行任何容器命令");
    }

    @ParameterizedTest
    @CsvSource({"pom.xml, mvnw, sh ./mvnw -B -ntp -o verify", "pom.xml, '', mvn -B -ntp -o verify",
        "build.gradle, gradlew, sh ./gradlew --no-daemon --offline build",
        "build.gradle.kts, '', gradle --no-daemon --offline build"})
    void choosesOfflineBuildAndStopsWhenToolchainIsMissing(String manifest, String wrapper, String expected)
        throws Exception {
        Files.writeString(root.resolve("source"), "old");
        Files.writeString(root.resolve(manifest), "");
        if (!wrapper.isEmpty()) {
            Files.writeString(root.resolve(wrapper), "");
        }
        var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER);
        when(backend.available()).thenReturn(true);
        List<String> commands = new ArrayList<>();
        when(backend.run(any())).thenAnswer(call -> {
            ExecutionRequest request = call.getArgument(0);
            commands.add(request.script());
            return new ExecutionResult(0, "ok", false, false, "mock", ExecutionIsolation.CONTAINER, "");
        });
        var gate = automaticGate(backend);
        assertTrue(gate.verify(root, "", changes()).ok());
        assertEquals(2, commands.size());
        assertTrue(commands.getFirst().contains("command -v java"));
        assertEquals(expected, commands.getLast());

        commands.clear();
        doAnswer(call -> {
            commands.add(((ExecutionRequest)call.getArgument(0)).script());
            return new ExecutionResult(127, "缺少工具链", false, false, "mock", ExecutionIsolation.CONTAINER, "");
        }).when(backend).run(any());
        assertEquals("failed", gate.verify(root, "", changes()).status());
        assertEquals(1, commands.size(), "工具链探测失败后不能继续执行项目构建");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pnpm@9", "yarn@4"})
    void rejectsUnsupportedPackageManagersBeforeExecutingCommands(String manager) throws Exception {
        Files.writeString(root.resolve("source"), "old");
        Files.writeString(root.resolve("package.json"),
            "{\"packageManager\":\"" + manager + "\",\"scripts\":{\"test\":\"node --test\"}}");
        var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER);
        when(backend.available()).thenReturn(true);
        var report = automaticGate(backend).verify(root, "", changes());
        assertEquals("failed", report.status());
        assertTrue(report.detail().contains("其他包管理器"));
        verify(backend, never()).run(any());
    }

    @Test
    void timeoutKeepsBoundedDiagnosticsAndCleansUpCandidate() throws Exception {
        Files.writeString(root.resolve("source"), "old");
        var backend = mock(ExecutionBackend.class);
        when(backend.isolation()).thenReturn(ExecutionIsolation.CONTAINER);
        when(backend.available()).thenReturn(true);
        AtomicReference<Path> candidate = new AtomicReference<>();
        when(backend.run(any())).thenAnswer(call -> {
            candidate.set(((ExecutionRequest)call.getArgument(0)).workspaceRoot());
            return new ExecutionResult(124, "prefix" + "x".repeat(5_000) + "last-output", true, false, "mock",
                ExecutionIsolation.CONTAINER, "last-error");
        });
        var report = gate(backend).verify(root, "", changes());
        assertEquals("failed", report.status());
        assertTrue(report.detail().contains("命令超时"));
        assertTrue(report.detail().contains("当前网络关闭"));
        assertTrue(report.detail().contains("last-output\nlast-error"));
        assertFalse(report.detail().contains("prefix"));
        assertTrue(report.detail().length() < 4_200);
        assertFalse(Files.exists(candidate.get()));
        assertEquals("old", Files.readString(root.resolve("source")));
    }

    private CommandVerificationGate automaticGate(ExecutionBackend backend) {
        var selection = new ExecutionBackends.Selection(backend, ExecutionIsolation.CONTAINER, false, "",
            ExecutionSettings.local());
        return new CommandVerificationGate(new MultiAgentProperties(true, 2, 1, 10, 10, List.of()),
            new PilotDataDir(root.resolve(".pilot").toString()), selection);
    }
}
