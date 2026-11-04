package com.pilot.core.exec;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.tool.ProcessSupport;

/**
 * 一次受控命令执行。
 *
 * @param argv          宿主侧命令与参数；LOCAL 后端直接使用
 * @param script        POSIX shell 脚本；容器后端用它执行，为空时由 argv 拼接
 * @param workdir       工作目录；容器后端要求它位于 workspaceRoot 内
 * @param workspaceRoot 容器可见的临时工作区；可另挂专用依赖缓存
 * @param env           追加环境变量；会先经过 {@link EnvironmentSanitizer}
 * @param network       是否允许容器访问网络
 */
public record ExecutionRequest(List<String> argv, String script, Path workdir, Path workspaceRoot,
    Map<String, String> env, boolean network, ExecutionLimits limits, Path dependencyCache) {
    public ExecutionRequest {
        // argv 是已分词参数，空参数和首尾空格都有语义，不能像命令文本一样裁剪。
        argv = argv == null ? List.of() : List.copyOf(argv);
        script = script == null || script.isBlank() ? "" : script.strip();
        if (argv.isEmpty() && script.isEmpty()) {
            throw new IllegalArgumentException("argv or script must not be empty");
        }
        if (!argv.isEmpty() && argv.getFirst().isBlank()) {
            throw new IllegalArgumentException("executable must not be blank");
        }
        Objects.requireNonNull(workdir, "workdir must not be null");
        workspaceRoot = workspaceRoot == null ? workdir : workspaceRoot;
        env = env == null ? Map.of() : Map.copyOf(env);
        limits = limits == null ? ExecutionLimits.defaults() : limits;
    }

    public ExecutionRequest(List<String> argv, String script, Path workdir, Path workspaceRoot, Map<String, String> env,
        boolean network, ExecutionLimits limits) {
        this(argv, script, workdir, workspaceRoot, env, network, limits, null);
    }

    /** 专用项目依赖缓存，不能指向宿主用户的 .m2、.npm 或真实工作区。 */
    public ExecutionRequest withDependencyCache(Path cache) {
        return new ExecutionRequest(argv, script, workdir, workspaceRoot, env, network, limits, cache);
    }

    /** 以宿主平台 shell 执行一条命令行（LOCAL 后端的常见入口）。 */
    public static ExecutionRequest shell(String command, Path workdir, Path workspaceRoot, Map<String, String> env,
        boolean network, ExecutionLimits limits) {
        return new ExecutionRequest(ProcessSupport.shell(command), command, workdir, workspaceRoot, env, network,
            limits);
    }

    public String commandLine() {
        return script.isEmpty() ? String.join(" ", argv) : script;
    }
}
