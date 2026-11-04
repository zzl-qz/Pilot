package com.pilot.core.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 本机子进程后端。
 *
 * <p>只落实「应用层能管住」的部分：环境变量过滤、工作目录、超时、输出上限、进程树清理。
 * 它<strong>不</strong>限制子进程读写其他目录、访问网络或 fork 大量进程——这些需要容器或
 * 操作系统沙箱。界面与诊断必须如实标注 {@link ExecutionIsolation#LOCAL}。
 */
public final class LocalExecutionBackend implements ExecutionBackend {
    private final EnvironmentSanitizer sanitizer;

    public LocalExecutionBackend(EnvironmentSanitizer sanitizer) {
        this.sanitizer = sanitizer == null ? EnvironmentSanitizer.defaults() : sanitizer;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public ExecutionIsolation isolation() {
        return ExecutionIsolation.LOCAL;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return "";
    }

    @Override
    public ExecutionResult run(ExecutionRequest request) {
        ProcessBuilder builder = new ProcessBuilder(request.argv());
        builder.directory(request.workdir().toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.clear();
        env.putAll(sanitizer.merge(System.getenv(), request.env()));

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return ExecutionResult.failure(id(), isolation(), "无法启动命令：" + e.getMessage());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // stdin 关闭失败不影响执行
        }

        CompletableFuture<Captured> output =
            CompletableFuture.supplyAsync(() -> read(process.getInputStream(), request.limits().maxOutputBytes()));
        boolean finished;
        try {
            long timeout = request.limits().timeoutSeconds();
            finished = timeout <= 0 || process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return ExecutionResult.failure(id(), isolation(), "命令执行被中断");
        }
        if (!finished) {
            killTree(process);
            Captured captured = await(output);
            return new ExecutionResult(-1, captured.text(), true, captured.truncated(), id(), isolation(), "");
        }
        Captured captured = await(output);
        return new ExecutionResult(process.exitValue(), captured.text(), false, captured.truncated(), id(), isolation(),
            "");
    }

    @Override
    public List<String> describe(ExecutionRequest request) {
        List<String> lines = new ArrayList<>();
        lines.add("后端：" + id() + "（无系统级隔离）");
        lines.add("工作目录：" + request.workdir());
        lines.add("超时：" + request.limits().timeoutSeconds() + " 秒");
        lines.add("输出上限：" + request.limits().maxOutputBytes() + " 字节");
        lines.add("网络：" + (request.network() ? "允许（继承宿主）" : "不限制（本机进程无法强制）"));
        return lines;
    }

    private static void killTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // 进程可能已经退出
            }
        });
        process.destroyForcibly();
    }

    private static Captured await(CompletableFuture<Captured> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return new Captured("", false);
        }
    }

    private static Captured read(InputStream stream, int limit) {
        try (InputStream input = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8_192];
            boolean truncated = false;
            int read;
            while ((read = input.read(chunk)) >= 0) {
                int remaining = limit - buffer.size();
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                buffer.write(chunk, 0, Math.min(read, remaining));
                if (read > remaining) {
                    truncated = true;
                    break;
                }
            }
            return new Captured(buffer.toString(StandardCharsets.UTF_8), truncated);
        } catch (IOException e) {
            return new Captured("", false);
        }
    }

    private record Captured(String text, boolean truncated) {
    }

    /** 便于调用方判断工作目录是否真实存在。 */
    static void requireDirectory(Path workdir) {
        if (workdir == null || !java.nio.file.Files.isDirectory(workdir)) {
            throw new IllegalArgumentException("工作目录不存在: " + workdir);
        }
    }
}
