package com.pilot.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ContainerExecutionBackendTest {

    private static ExecutionRequest request(Path root, Path workdir) {
        return ExecutionRequest.shell("mvn -q test", workdir, root,
            Map.of("CI", "true", "API_TOKEN", "should-not-leak"), false,
            new ExecutionLimits(120, 100_000, 1_024, 1.5, 128));
    }

    @Test
    void argvCarriesEveryIsolationBoundary() {
        ContainerExecutionBackend backend =
            new ContainerExecutionBackend("docker", "pilot/java:25", "/workspace", "1000:1000");
        Path root = Path.of("/repo").toAbsolutePath();
        List<String> argv = backend.argv(request(root, root.resolve("sub")), root, "/workspace/sub");

        assertTrue(argv.contains("--network=none"), argv.toString());
        assertTrue(argv.contains("--read-only"));
        assertTrue(argv.contains("--cap-drop"));
        assertTrue(argv.contains("ALL"));
        assertTrue(argv.contains("no-new-privileges"));
        assertTrue(argv.contains("--pids-limit"));
        assertTrue(argv.contains("128"));
        assertTrue(argv.contains("--memory"));
        assertTrue(argv.contains("1024m"));
        assertTrue(argv.contains("--cpus"));
        assertTrue(argv.contains("1.5"));
        assertTrue(argv.contains("--mount"));
        assertTrue(argv.contains("type=bind,\"source=" + root + "\",\"target=/workspace\""));
        assertTrue(argv.contains("--pull=never"));
        assertTrue(argv.contains("-w"));
        assertTrue(argv.contains("/workspace/sub"));
        assertTrue(argv.contains("pilot/java:25"));
        assertTrue(argv.contains("sh"));
        assertTrue(argv.contains("mvn -q test"), "脚本必须在容器内以 POSIX shell 执行");
    }

    @Test
    void onlySanitizedEnvironmentIsPassedIntoTheContainer() {
        ContainerExecutionBackend backend = new ContainerExecutionBackend("docker", "img", "/workspace", "");
        Path root = Path.of("/repo").toAbsolutePath();
        List<String> argv = backend.argv(request(root, root), root, "/workspace");

        assertTrue(argv.contains("CI=true"));
        assertFalse(argv.stream().anyMatch(value -> value.startsWith("API_TOKEN=")), "带凭据字样的变量不能进入容器");
    }

    @Test
    void networkIsOptIn() {
        ContainerExecutionBackend backend = new ContainerExecutionBackend("docker", "img", "/workspace", "");
        Path root = Path.of("/repo").toAbsolutePath();
        ExecutionRequest networked =
            ExecutionRequest.shell("npm test", root, root, Map.of(), true, ExecutionLimits.defaults());

        assertTrue(backend.argv(networked, root, "/workspace").contains("--network=bridge"));
    }

    @Test
    void refusesToRunOutsideTheWorkspaceMount() {
        ContainerExecutionBackend backend = new ContainerExecutionBackend("docker", "img", "/workspace", "");
        Path root = Path.of("/repo").toAbsolutePath();
        ExecutionResult result = backend.run(request(root, Path.of("/etc")));

        // 无容器运行时也应先给出明确失败，而不是静默在本机执行
        assertFalse(result.ok());
        assertFalse(result.error().isEmpty());
    }

    @Test
    void reportsWhyIsolationIsUnavailable() {
        ContainerExecutionBackend backend =
            new ContainerExecutionBackend("definitely-not-installed", "img", "/workspace", "");
        assertFalse(backend.available());
        assertTrue(backend.unavailableReason().contains("容器运行时不可用") || backend.unavailableReason().contains("容器"),
            backend.unavailableReason());
        ExecutionResult result = backend.run(request(Path.of("."), Path.of(".")));
        assertEquals(ExecutionIsolation.CONTAINER, result.isolation());
        assertFalse(result.error().isEmpty());
    }
}
