package com.pilot.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.command.SandboxCommandTool;
import com.pilot.core.workspace.PathValidator;

/** 显式启用真实 Docker/Podman 验证；只用已拉取镜像，不访问模型、不下载依赖。 */
@EnabledIfSystemProperty(named = "pilot.test.container.image", matches = ".+")
class ContainerIsolationIntegrationTest {
    @TempDir
    Path root;
    private final String runtime = System.getProperty("pilot.test.container.runtime", "docker");

    private ContainerExecutionBackend backend() {
        var backend =
            new ContainerExecutionBackend(runtime, System.getProperty("pilot.test.container.image"), "/workspace", "");
        assertTrue(backend.available(), backend.unavailableReason());
        return backend;
    }

    private ExecutionRequest request(Path copy, String script, long timeout) {
        return ExecutionRequest.shell(script, copy, copy, Map.of("CI", "true", "API_TOKEN", "secret"), false,
            new ExecutionLimits(timeout, 20000, 128, 0.5, 64));
    }

    private Set<String> containers() throws Exception {
        var result = new LocalExecutionBackend(EnvironmentSanitizer.defaults())
            .run(new ExecutionRequest(List.of(runtime, "ps", "-aq", "--filter", "label=pilot.execution=true"), "", root,
                root, Map.of(), false, new ExecutionLimits(10, 20000, 128, 0.5, 64)));
        assertTrue(result.ok(), result.output() + result.error());
        return new HashSet<>(result.output().lines().filter(line -> !line.isBlank()).toList());
    }

    @Test
    void enforcesFilesystemNetworkEnvironmentAndTemporaryProposalBoundary() throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("a"), "disk");
        Files.writeString(root.resolve("host-secret"), "private");
        try (var copy = IsolatedWorkspace.create(new PathValidator(source), Map.of("a", "candidate"))) {
            String hostPath = root.resolve("host-secret").toString().replace("'", "'\\''");
            var result = backend().run(request(copy.root(),
                "set -e; test \"$(cat a)\" = candidate; test \"$CI\" = true; " + "test -z \"$API_TOKEN\"; test ! -e '"
                    + hostPath + "'; test ! -S /var/run/docker.sock; "
                    + "test ! -d /sys/class/net/eth0; if touch /root/nope; then exit 1; fi; echo output > build.out",
                20));
            assertTrue(result.ok(), result.output() + result.error());
            assertEquals("output\n", Files.readString(copy.root().resolve("build.out")));
            copy.verifySourceUnchanged();
        }
        assertEquals("disk", Files.readString(source.resolve("a")));
        assertFalse(Files.exists(source.resolve("build.out")));
    }

    @Test
    void timeoutRemovesTheContainerRatherThanOnlyKillingItsClient() throws Exception {
        Set<String> before = containers();
        var result = backend().run(request(root, "sleep 60", 2));
        assertTrue(result.timedOut(), result.toString());
        assertFalse(result.ok());
        assertEquals(before, containers());
    }

    @Test
    void cancellationRemovesContainerAndPreservesInterruption() throws Exception {
        Set<String> before = containers();
        var backend = backend();
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                backend.run(request(root, "sleep 60", 30));
                interrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                finished.countDown();
            }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (containers().equals(before) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertNotEquals(before, containers(), "必须先确认容器已启动，再测试取消");
        } finally {
            thread.interrupt();
        }
        assertTrue(finished.await(15, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        assertEquals(before, containers());
    }

    @Test
    void bindMountHandlesSpacesCommasAndUnicodeWithoutShellExpansion() throws Exception {
        Path special = Files.createDirectory(root.resolve("目录 space,comma"));
        var result = backend().run(request(special, "printf 'ok' > result", 20));
        assertTrue(result.ok(), result.output() + result.error());
        assertEquals("ok", Files.readString(special.resolve("result")));
    }

    @Test
    void argvRetainsEmptyArgumentsWhitespaceAndLiteralShellCharacters() {
        var request = new ExecutionRequest(List.of("printf", "<%s>", "", " a ", "$(touch injected)", "it's literal"),
            "", root, root, Map.of(), false, new ExecutionLimits(20, 10000, 128, .5, 64));
        var result = backend().run(request);
        assertTrue(result.ok(), result.output() + result.error());
        assertEquals("<>< a ><$(touch injected)><it's literal>", result.output());
        assertFalse(Files.exists(root.resolve("injected")));
    }

    @Test
    void scopedCommandRunsPosixWrapperAgainstTheProposalWithoutExecutableBit() throws Exception {
        Files.writeString(root.resolve("a"), "disk");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\ncat a\n");
        var sink = new SandboxSink() {
            public void record(String path, String oldText, String newText) {
            }

            public Map<String, String> proposedFiles() {
                return Map.of("a", "candidate");
            }
        };
        var tool = new SandboxCommandTool(new PathValidator(root), sink, new ExecutionBackends.Selection(backend(),
            ExecutionIsolation.CONTAINER, false, "", ExecutionSettings.local()));
        for (String command : List.of("./mvnw test", ".\\mvnw.cmd test", "mvn test")) {
            String result = tool.execute(Map.of("command", command));
            assertTrue(result.contains("退出码: 0"), result);
            assertTrue(result.contains("candidate"), result);
        }
        assertEquals("disk", Files.readString(root.resolve("a")));
    }

    @Test
    void rejectsSymlinkWorkdirOutsideTheMountAndMissingImagesWithoutFallback() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path mount = Files.createDirectory(root.resolve("mount"));
        try {
            Files.createSymbolicLink(mount.resolve("link"), outside);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("当前平台没有创建符号链接的权限");
        }
        var escaping = ExecutionRequest.shell("touch bad", mount.resolve("link"), mount, Map.of(), false,
            ExecutionLimits.defaults());
        assertFalse(backend().run(escaping).ok());
        assertFalse(Files.exists(outside.resolve("bad")));
        var missing = new ContainerExecutionBackend(runtime, "pilot-image-does-not-exist:test", "/workspace", "");
        var result = missing.run(request(mount, "touch fallback", 10));
        assertFalse(result.ok());
        assertFalse(Files.exists(mount.resolve("fallback")));
    }
}
