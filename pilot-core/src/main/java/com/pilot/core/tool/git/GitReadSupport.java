package com.pilot.core.tool.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.pilot.core.exec.EnvironmentSanitizer;
import com.pilot.core.exec.ProcessSupport;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

/** 跨平台只读 Git 工具公共执行与安全校验。 */
final class GitReadSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final int MAX_BYTES = 256 * 1024;

    private GitReadSupport() {
    }

    static Availability availability(PathValidator paths) {
        return isInsideGitRepo(paths.root()) ? Availability.ready() : Availability.unavailable("当前工作区不是 Git 仓库");
    }

    static String repositoryError(PathValidator paths) {
        return isInsideGitRepo(paths.root()) ? null : "ERROR: 当前工作区不是 Git 仓库";
    }

    static boolean isInsideGitRepo(Path start) {
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            Path dotGit = dir.resolve(".git");
            if (!Files.isSymbolicLink(dotGit) && Files.exists(dotGit, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    static String resolvePathspec(PathValidator paths, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path resolved = paths.resolve(rawPath);
        String relative = paths.display(resolved).replace('\\', '/');
        if (relative.isEmpty() || ".".equals(relative)) {
            return null;
        }
        return ":(literal)" + relative;
    }

    static String resolveScopedPathspec(PathValidator paths, AgentScope scope, String rawPath) {
        if (scope == null) {
            return resolvePathspec(paths, rawPath);
        }
        boolean restricted = !scope.allowFiles().isEmpty() || !scope.denyFiles().isEmpty();
        if (rawPath == null || rawPath.isBlank()) {
            if (restricted) {
                throw new IllegalArgumentException("AgentScope 路径受限时必须指定 path");
            }
            return null;
        }
        Path resolved = paths.resolve(rawPath);
        String relative = paths.display(resolved).replace('\\', '/');
        if (relative.isEmpty() || ".".equals(relative)) {
            if (restricted) {
                throw new IllegalArgumentException("AgentScope 路径受限时必须指定具体的允许路径");
            }
            return null;
        }
        if (!scope.allowsPath(relative)) {
            throw new IllegalArgumentException("路径不在 AgentScope 允许范围内");
        }
        return ":(literal)" + relative;
    }

    /** 统一处理仓库校验、受限路径解析、附加 pathspec、执行 git 与标准失败格式化。 */
    static String runScoped(PathValidator paths, AgentScope scope, Map<String, Object> args, String commandLabel,
        List<String> baseCommand, Function<RunResult, String> formatter) {
        String repositoryError = repositoryError(paths);
        if (repositoryError != null) {
            return repositoryError;
        }
        String pathspec;
        try {
            pathspec = resolveScopedPathspec(paths, scope, ToolSupport.stringArg(args, "path"));
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        List<String> command = new ArrayList<>(baseCommand);
        if (pathspec != null) {
            command.add("--");
            command.add(pathspec);
        }
        RunResult result = run(paths.root(), command);
        String formatted = formatter.apply(result);
        if (formatted != null) {
            return formatted;
        }
        String detail = result.error() != null ? result.error() : result.output().strip();
        return "ERROR: git " + commandLabel + " 失败：" + detail;
    }

    static RunResult run(Path workspace, List<String> gitArgs) {
        List<String> command = new ArrayList<>(gitArgs.size() + 12);
        command.add("git");
        command.add("-c");
        command.add("core.quotepath=false");
        command.add("-c");
        command.add("core.fsmonitor=false");
        command.add("-c");
        command.add("diff.external=");
        command.add("-c");
        command.add("core.hooksPath=");
        if (!gitArgs.isEmpty() && "diff".equals(gitArgs.getFirst())) {
            command.add("diff");
            command.add("--no-ext-diff");
            command.add("--no-textconv");
            command.addAll(gitArgs.subList(1, gitArgs.size()));
        } else {
            command.addAll(gitArgs);
        }

        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile());
        Map<String, String> env = builder.environment();
        env.clear();
        env.putAll(EnvironmentSanitizer.defaults().inherit(System.getenv()));
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_PAGER", "cat");
        try {
            ProcessSupport.CapturedOutput captured = ProcessSupport.captureUtf8(builder, TIMEOUT, MAX_BYTES);
            return new RunResult(captured.exitCode(), captured.output(), null);
        } catch (TimeoutException e) {
            return new RunResult(-1, "", "git 命令超时（超过 " + TIMEOUT.toSeconds() + " 秒）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RunResult(-1, "", "git 命令被中断");
        } catch (IOException e) {
            return new RunResult(-1, "", "无法执行 git：" + e.getMessage());
        }
    }

    static String truncateLines(String raw, int maxLines, String hint) {
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = normalized.lines().toList();
        if (lines.size() <= maxLines) {
            return normalized.stripTrailing();
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines.get(i));
        }
        out.append("\n…（共 ").append(lines.size()).append(" 行，仅展示前 ").append(maxLines).append(" 行");
        if (hint != null && !hint.isBlank()) {
            out.append("；").append(hint);
        }
        out.append("）");
        return out.toString();
    }

    record RunResult(int exitCode, String output, String error) {
        boolean ok() {
            return error == null && exitCode == 0;
        }
    }
}
