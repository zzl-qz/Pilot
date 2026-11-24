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

import com.pilot.core.exec.ProcessSupport;
import com.pilot.core.tool.Availability;
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

    static RunResult run(Path workspace, List<String> gitArgs) {
        List<String> command = new ArrayList<>(gitArgs.size() + 3);
        command.add("git");
        command.add("-c");
        command.add("core.quotepath=false");
        command.addAll(gitArgs);

        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile());
        Map<String, String> env = builder.environment();
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

    static int intArg(Map<String, Object> args, String key, int fallback, int min, int max) {
        if (args == null || args.get(key) == null) {
            return fallback;
        }
        Object raw = args.get(key);
        int parsed;
        if (raw instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(raw).strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    static boolean boolArg(Map<String, Object> args, String key) {
        if (args == null) {
            return false;
        }
        Object raw = args.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw instanceof String s && "true".equalsIgnoreCase(s.strip());
    }

    record RunResult(int exitCode, String output, String error) {
        boolean ok() {
            return error == null && exitCode == 0;
        }
    }
}
