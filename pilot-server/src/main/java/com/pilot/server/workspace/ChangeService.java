package com.pilot.server.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 变更审阅：读取工作区的 git 状态与 diff，供界面展示「这次到底改了什么」。
 * 只读操作，不修改仓库；工作区不是 git 仓库时返回 {@code gitRepository=false}。
 */
@Component
public class ChangeService {
    private static final int MAX_DIFF_CHARS = 200_000;
    private static final int MAX_FILES = 500;
    private static final long TIMEOUT_SECONDS = 15;

    public record FileChange(String status, String path) {
    }

    /**
     * @param diff 未暂存改动的 unified diff；仓库不是 git 仓库时为空字符串
     */
    public record ChangeReport(String workspace, boolean gitRepository, List<FileChange> files,
                               String diff, boolean truncated, String error) {
    }

    public ChangeReport inspect(Path workspace) {
        String root = workspace.toString();
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return new ChangeReport(root, false, List.of(), "", false, null);
        }

        String status = exec(workspace, List.of("git", "status", "--porcelain=v1", "-uall"));
        String diff = stripNoise(exec(workspace, List.of("git", "diff", "--no-color", "--no-ext-diff", "--", ".")));

        var files = new ArrayList<FileChange>();
        boolean truncated = false;
        for (String line : status.lines().toList()) {
            if (line.length() < 3) {
                continue;
            }
            if (files.size() >= MAX_FILES) {
                truncated = true;
                break;
            }
            String code = line.substring(0, 2).strip();
            String path = line.substring(3).strip();
            int renameMark = path.indexOf(" -> ");
            if (renameMark > 0) {
                path = path.substring(renameMark + 4);
            }
            files.add(new FileChange(code.isEmpty() ? "?" : code, path));
        }

        boolean diffTruncated = diff.length() > MAX_DIFF_CHARS;
        String body = diffTruncated ? diff.substring(0, MAX_DIFF_CHARS) + "\n… diff 已截断 …" : diff;
        return new ChangeReport(root, true, List.copyOf(files), body, truncated || diffTruncated, null);
    }

    /** 去掉 git 输出里与 diff 无关的告警行（例如 Windows 的 LF/CRLF 提示）。 */
    private static String stripNoise(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("warning: ")) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    private static String exec(Path workspace, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var stream = process.getInputStream()) {
                    return stream.readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                return "";
            }
            return new String(output.join(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
