package com.pilot.core.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 容器执行后端。
 *
 * <p>容器提供的是 Pilot 自己实现不了的那部分边界：独立文件系统、可关闭的网络、CPU/内存/进程数上限、
 * 丢弃 capabilities、禁止提权。挂载目录仍然可写，调用方必须传入临时副本；容器本身不隔离 bind mount 的写入。
 *
 * <p>可用性必须实测：没有 Docker/Podman、守护进程没启动、镜像不存在时明确报告原因，
 * 而不是悄悄退化成普通子进程。{@link #available()} 只探测运行时，每次执行前检查本地镜像，不自动拉取或打开网络。
 */
public final class ContainerExecutionBackend implements ExecutionBackend {
    private static final Set<String> CLI_ENV = Set.of("DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CERT_PATH",
        "DOCKER_TLS_VERIFY", "XDG_RUNTIME_DIR", "CONTAINER_HOST");

    private final String runtime;
    private final String image;
    private final String containerWorkspace;
    private final String containerUser;
    private final LocalExecutionBackend cli;
    private volatile String unavailableReason;
    private volatile boolean probed;

    public ContainerExecutionBackend(String runtime, String image, String containerWorkspace, String containerUser) {
        this.runtime = runtime == null || runtime.isBlank() ? detectRuntime() : runtime.strip();
        this.image = image == null || image.isBlank() ? "eclipse-temurin:25-jdk" : image.strip();
        this.containerWorkspace =
            containerWorkspace == null || containerWorkspace.isBlank() ? "/workspace" : containerWorkspace.strip();
        this.containerUser = containerUser == null ? "" : containerUser.strip();
        this.cli = new LocalExecutionBackend(new EnvironmentSanitizer(CLI_ENV));
    }

    @Override
    public String id() {
        return "container:" + runtime;
    }

    @Override
    public ExecutionIsolation isolation() {
        return ExecutionIsolation.CONTAINER;
    }

    @Override
    public boolean available() {
        if (!probed) {
            probe();
        }
        return unavailableReason == null;
    }

    @Override
    public String unavailableReason() {
        if (!probed) {
            probe();
        }
        return unavailableReason == null ? "" : unavailableReason;
    }

    private synchronized void probe() {
        if (probed) {
            return;
        }
        if (runtime.isBlank()) {
            unavailableReason = "没有找到 Docker 或 Podman 可执行文件；设置 PILOT_CONTAINER_RUNTIME 指向容器运行时";
            probed = true;
            return;
        }
        ExecutionResult version =
            cli.run(new ExecutionRequest(List.of(runtime, "info", "--format", "{{.ServerVersion}}"), "", Path.of("."),
                Path.of("."), Map.of(), false, new ExecutionLimits(5, 10_000, 256, 0.5, 64)));
        if (!version.ok()) {
            unavailableReason = "容器运行时不可用（" + runtime + " info 失败）："
                + firstLine(version.error().isEmpty() ? version.output() : version.error());
        }
        probed = true;
    }

    @Override
    public String cacheIdentity() {
        if (!available()) {
            throw new IllegalStateException("容器隔离不可用：" + unavailableReason());
        }
        ExecutionResult inspect =
            cli.run(new ExecutionRequest(List.of(runtime, "image", "inspect", "--format", "{{.Id}}", "--", image), "",
                Path.of("."), Path.of("."), Map.of(), false, new ExecutionLimits(10, 1024, 256, 0.5, 64)));
        if (!inspect.ok() || inspect.output().isBlank()) {
            throw new IllegalStateException("构建镜像未就绪：" + image + "。请先通过容器运行时拉取或构建此镜像；不会自动下载或在宿主机执行。");
        }
        return image + "@" + inspect.output().strip();
    }

    @Override
    public ExecutionResult run(ExecutionRequest request) {
        if (!available()) {
            return ExecutionResult.failure(id(), isolation(), "容器隔离不可用：" + unavailableReason);
        }
        try {
            cacheIdentity();
        } catch (IllegalStateException e) {
            return ExecutionResult.failure(id(), isolation(), e.getMessage());
        }
        Path workdir;
        Path root;
        try {
            workdir = request.workdir().toRealPath();
            root = request.workspaceRoot().toRealPath();
            if (request.dependencyCache() != null) {
                DependencyCache.validateMount(request.dependencyCache(), root);
            }
        } catch (java.io.IOException e) {
            return ExecutionResult.failure(id(), isolation(), "无法解析容器工作区：" + e.getMessage());
        }
        if (!workdir.startsWith(root)) {
            return ExecutionResult.failure(id(), isolation(), "工作目录不在允许挂载的工作区内：" + workdir + "（工作区 " + root + "）");
        }
        if (!java.nio.file.Files.isDirectory(workdir)) {
            return ExecutionResult.failure(id(), isolation(), "工作目录不存在：" + workdir);
        }
        String relative = root.relativize(workdir).toString().replace('\\', '/');
        String workingDir = relative.isEmpty() ? containerWorkspace : containerWorkspace + "/" + relative;

        String name = "pilot-exec-" + java.util.UUID.randomUUID();
        List<String> argv = new ArrayList<>(argv(request, root, workingDir));
        argv.addAll(2, List.of("--name", name, "--label", "pilot.execution=true"));
        ExecutionLimits limits = request.limits();
        ExecutionResult cliResult;
        try {
            cliResult = cli.run(new ExecutionRequest(argv, "", root, root, Map.of(), false,
                new ExecutionLimits(Math.max(1, limits.timeoutSeconds()), limits.maxOutputBytes(), limits.memoryMb(),
                    limits.cpus(), limits.pidsLimit())));
        } finally {
            // 杀掉 docker 客户端不等于终止容器。只清理本次生成的名字，不接触用户容器。
            boolean interrupted = Thread.interrupted();
            try {
                cli.run(new ExecutionRequest(List.of(runtime, "rm", "-f", name), "", root, root, Map.of(), false,
                    new ExecutionLimits(10, 10000, 256, 0.5, 64)));
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (!cliResult.error().isEmpty()) {
            return ExecutionResult.failure(id(), isolation(), "容器启动失败：" + cliResult.error());
        }
        return new ExecutionResult(cliResult.exitCode(), cliResult.output(), cliResult.timedOut(),
            cliResult.truncated(), id(), isolation(), "");
    }

    /**
     * 组装 {@code docker run} 参数。做成包内可见的纯函数，才能在没有容器的环境里验证边界：
     * 网络、只读根文件系统、capabilities、资源上限、挂载点与传入的环境变量。
     */
    List<String> argv(ExecutionRequest request, Path root, String workingDir) {
        List<String> argv = new ArrayList<>();
        argv.add(runtime);
        argv.add("run");
        argv.add("--rm");
        argv.add("--pull=never");
        argv.add("--init");
        argv.add("--network=" + (request.network() ? "bridge" : "none"));
        argv.add("--read-only");
        argv.add("--tmpfs");
        argv.add("/tmp:rw,noexec,nosuid,size=256m");
        argv.add("--cap-drop");
        argv.add("ALL");
        argv.add("--security-opt");
        argv.add("no-new-privileges");
        argv.add("--pids-limit");
        argv.add(String.valueOf(request.limits().pidsLimit()));
        argv.add("--memory");
        argv.add(request.limits().memoryMb() + "m");
        argv.add("--cpus");
        argv.add(String.valueOf(request.limits().cpus()));
        // Linux bind mount 的构建输出必须归宿主工作区所有者，避免留下 root 所有且无法清理的目录。
        argv.add("--user");
        argv.add(containerUser.isEmpty() ? workspaceOwner(root) : containerUser);
        argv.add("--mount");
        argv.add("type=bind,\"source=" + root.toString().replace("\"", "\"\"") + "\",\"target="
            + containerWorkspace.replace("\"", "\"\"") + "\"");
        if (request.dependencyCache() != null) {
            argv.add("--mount");
            argv.add("type=bind,\"source="
                + request.dependencyCache().toAbsolutePath().normalize().toString().replace("\"", "\"\"")
                + "\",target=/pilot-cache");
            // 只注入固定的容器路径，不继承宿主包管理器配置或凭据。
            for (String value : List.of("NPM_CONFIG_CACHE=/pilot-cache/npm", "GRADLE_USER_HOME=/pilot-cache/gradle",
                "MAVEN_OPTS=-Dmaven.repo.local=/pilot-cache/maven -Duser.home=/tmp/pilot-home",
                "MAVEN_USER_HOME=/pilot-cache/maven-wrapper")) {
                argv.add("-e");
                argv.add(value);
            }
        }
        argv.add("-w");
        argv.add(workingDir);
        for (Map.Entry<String, String> entry : sanitizedEnv(request.env()).entrySet()) {
            argv.add("-e");
            argv.add(entry.getKey() + "=" + entry.getValue());
        }
        argv.add(image);
        argv.add("sh");
        argv.add("-lc");
        argv.add(script(request));
        return argv;
    }

    @Override
    public List<String> describe(ExecutionRequest request) {
        Map<String, String> env = sanitizedEnv(request.env());
        List<String> lines = new ArrayList<>();
        lines.add("后端：" + id() + "（镜像 " + image + "）");
        lines.add("网络：" + (request.network() ? "bridge" : "none"));
        lines.add("根文件系统：只读；/tmp 为受限 tmpfs");
        lines.add("资源上限：" + request.limits().memoryMb() + "MB / " + request.limits().cpus() + " CPU / "
            + request.limits().pidsLimit() + " 进程");
        lines.add("挂载：" + request.workspaceRoot().toAbsolutePath().normalize() + " → " + containerWorkspace);
        lines.add("依赖缓存：" + (request.dependencyCache() == null ? "未启用" : "项目专用 /pilot-cache（不共享宿主缓存）"));
        lines.add("传入环境变量：" + (env.isEmpty() ? "(无)" : String.join(",", env.keySet())));
        return lines;
    }

    private static Map<String, String> sanitizedEnv(Map<String, String> extra) {
        // 宿主环境一个都不传：容器里只保留调用方显式声明、且不带凭据字样的变量
        Map<String, String> result = new LinkedHashMap<>();
        EnvironmentSanitizer sanitizer = EnvironmentSanitizer.defaults();
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            if (sanitizer.allowed(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static String script(ExecutionRequest request) {
        if (!request.script().isEmpty()) {
            return request.script();
        }
        return String.join(" ", request.argv().stream().map(word -> "'" + word.replace("'", "'\\''") + "'").toList());
    }

    private static String workspaceOwner(Path root) {
        try {
            Object uid = java.nio.file.Files.getAttribute(root, "unix:uid");
            Object gid = java.nio.file.Files.getAttribute(root, "unix:gid");
            return uid + ":" + gid;
        } catch (java.io.IOException | UnsupportedOperationException | IllegalArgumentException e) {
            // Windows / 非 POSIX 文件系统：Docker Desktop 映射目录，使用普通容器用户。
            return "1000:1000";
        }
    }

    private static String detectRuntime() {
        String configured = System.getenv("PILOT_CONTAINER_RUNTIME");
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        for (String candidate : List.of("docker", "podman")) {
            if (onPath(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> names =
            os.contains("win") ? List.of(executable + ".exe", executable + ".cmd", executable) : List.of(executable);
        for (String directory : path.split(java.io.File.pathSeparator)) {
            for (String name : names) {
                try {
                    if (java.nio.file.Files.isRegularFile(Path.of(directory, name))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // 非法 PATH 片段直接跳过
                }
            }
        }
        return false;
    }

    private static String firstLine(String text) {
        String value = text == null ? "" : text.strip();
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }
}
