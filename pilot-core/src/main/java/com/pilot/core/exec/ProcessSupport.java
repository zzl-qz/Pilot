package com.pilot.core.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;

import com.pilot.core.util.HostOs;

/**
 * 子进程执行的公共细节：跨平台 shell 包装与输出解码。
 *
 * <p>Windows 上子进程的 stdout/stderr 常用本地编码（GBK），直接按 UTF-8 解码会把中文变成替换字符，
 * 模型读到一堆乱码就无法判断构建是否真的通过；因此统一「严格 UTF-8，失败回退 GBK」。
 */
public final class ProcessSupport {
    /** Windows 中文环境的默认编码，作为非 UTF-8 输出的回退。 */
    private static final Charset GBK = Charset.forName("GBK");
    /** UTF-8 单个字符最多 4 字节；输出被截断时最多缺 3 个字节。 */
    private static final int MAX_TRUNCATED_BYTES = 3;
    private static final boolean WINDOWS = HostOs.isWindows();

    private ProcessSupport() {
    }

    /** 用平台 shell 执行一条命令行：Windows 走 {@code cmd.exe /c}，其余走 {@code /bin/sh -c}。 */
    public static List<String> shell(String command) {
        return WINDOWS ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-c", command);
    }

    /** 执行安装命令，将合并后的输出写入日志。 */
    public static int runLogged(ProcessBuilder builder, Path logFile, Duration timeout, Logger log) {
        try {
            return runRedirected(builder, ProcessBuilder.Redirect.appendTo(logFile.toFile()), timeout);
        } catch (TimeoutException e) {
            log.warn("命令超时：{}", String.join(" ", builder.command()));
            return -1;
        } catch (IOException e) {
            log.warn("命令无法执行：{}（{}）", String.join(" ", builder.command()), e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** 不需要输出的命令直接重定向，避免创建输出管道。 */
    public static int runDiscarded(ProcessBuilder builder, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
        return runRedirected(builder, ProcessBuilder.Redirect.DISCARD, timeout);
    }

    private static int runRedirected(ProcessBuilder builder, ProcessBuilder.Redirect output, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
        Process process = builder.redirectErrorStream(true).redirectOutput(output).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout)) {
                throw new TimeoutException();
            }
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
        }
    }

    public record CapturedOutput(int exitCode, String output) {
    }

    /** 捕获合并输出的开头，超出上限的字节仍继续消费。退出与读完输出共享同一个期限。 */
    public static CapturedOutput captureUtf8(ProcessBuilder builder, Duration timeout, int maxBytes)
        throws IOException, InterruptedException, TimeoutException {
        return captureUtf8Internal(builder, true, null, timeout, maxBytes);
    }

    /** 仅捕获 stdout（stderr 丢弃），供 ripgrep/git ls-files 等只读查询命令共享。 */
    public static CapturedOutput captureStdoutUtf8(ProcessBuilder builder, Duration timeout, int maxBytes)
        throws IOException, InterruptedException, TimeoutException {
        return captureUtf8Internal(builder, false, null, timeout, maxBytes);
    }

    /** 写入 stdin 并捕获合并输出的开头，写入与读取并发执行且共享同一个期限。 */
    public static CapturedOutput captureUtf8WithStdin(ProcessBuilder builder, byte[] stdin, Duration timeout,
        int maxBytes) throws IOException, InterruptedException, TimeoutException {
        return captureUtf8Internal(builder, true, stdin, timeout, maxBytes);
    }

    /** 写入 stdin 并仅捕获 stdout（stderr 丢弃），供 NUL 分隔或结构化输出命令共享。 */
    public static CapturedOutput captureStdoutUtf8WithStdin(ProcessBuilder builder, byte[] stdin, Duration timeout,
        int maxBytes) throws IOException, InterruptedException, TimeoutException {
        return captureUtf8Internal(builder, false, stdin, timeout, maxBytes);
    }

    private static CapturedOutput captureUtf8Internal(ProcessBuilder builder, boolean mergeStderr, byte[] stdin,
        Duration timeout, int maxBytes) throws IOException, InterruptedException, TimeoutException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be nonnegative");
        }
        long budget = timeout.toNanos();
        if (mergeStderr) {
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.PIPE);
        } else {
            builder.redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.PIPE);
        }
        Process process = builder.start();
        long deadline = System.nanoTime() + budget;
        var output = new FutureTask<>(
            () -> new String(readBoundedBytes(process.getInputStream(), maxBytes), StandardCharsets.UTF_8));
        FutureTask<Void> writer = null;
        try {
            Thread.startVirtualThread(output);
            if (stdin == null || stdin.length == 0) {
                process.getOutputStream().close();
            } else {
                writer = new FutureTask<>(() -> {
                    try (var out = process.getOutputStream()) {
                        out.write(stdin);
                    } catch (IOException ignored) {
                        // 子进程提前退出或超时关闭 stdin 时停止写入
                    }
                    return null;
                });
                Thread.startVirtualThread(writer);
            }
            if (!process.waitFor(Duration.ofNanos(deadline - System.nanoTime()))) {
                throw new TimeoutException();
            }
            // 进程已退出，给读线程留出最小收尾余量以完成 EOF 读取和解码，避免临近 deadline 时误判超时
            long readNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(500), deadline - System.nanoTime());
            return new CapturedOutput(process.exitValue(), output.get(readNanos, TimeUnit.NANOSECONDS));
        } catch (ExecutionException e) {
            throw new IOException("无法读取命令输出", e.getCause());
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // 忽略已关闭的 stdin
            }
            output.cancel(true);
            if (writer != null) {
                writer.cancel(true);
            }
        }
    }

    /** 强制终止进程及其全部后代进程；单条后代终止失败不阻断其余清理。 */
    public static void terminate(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = List.of();
        try {
            descendants = process.descendants().toList();
        } catch (RuntimeException ignored) {
            // 受限环境或测试桩可能不支持 descendants
        }
        try {
            process.toHandle().destroyForcibly();
        } catch (RuntimeException ignored) {
            process.destroyForcibly();
        }
        for (ProcessHandle handle : descendants) {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // 子进程可能已退出
            }
        }
    }

    /** 先温和请求进程树退出并等待 {@code grace}，超时或中断再强制终止。 */
    public static void terminateGracefully(Process process, Duration grace) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = List.of();
        try {
            descendants = process.descendants().toList();
        } catch (RuntimeException ignored) {
            // 受限环境或测试桩可能不支持 descendants
        }
        for (ProcessHandle handle : descendants) {
            try {
                handle.destroy();
            } catch (RuntimeException ignored) {
                // 子进程可能已退出
            }
        }
        try {
            process.destroy();
        } catch (RuntimeException ignored) {
            // 忽略已退出进程
        }
        if (grace != null && !grace.isNegative() && !grace.isZero()) {
            try {
                if (process.waitFor(grace)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        terminate(process);
    }

    /** 保留至多 {@code maxBytes} 字节，超出上限仍继续排空流避免阻塞写端，结束时关闭流。 */
    public static byte[] readBoundedBytes(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be nonnegative");
        }
        try (input) {
            byte[] buffer = new byte[8192];
            var output = new ByteArrayOutputStream(Math.min(maxBytes, buffer.length));
            int count;
            while ((count = input.read(buffer)) != -1) {
                int remaining = maxBytes - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(count, remaining));
                }
            }
            return output.toByteArray();
        }
    }

    /**
     * 优先按 UTF-8 严格解码；出现非法字节时回退 GBK。
     *
     * <p>输出被字节上限截断时可能正好切在多字节序列中间，那种场景仍应按 UTF-8 解码，
     * 所以先退回最多 3 个字节再试，试不出来才认定是本地编码。否则一段被截断的中文会整段变成乱码。
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String text = utf8OrNull(bytes, bytes.length);
        for (int trim = 1; text == null && trim <= MAX_TRUNCATED_BYTES && trim < bytes.length; trim++) {
            text = utf8OrNull(bytes, bytes.length - trim);
        }
        return text == null ? new String(bytes, GBK) : text;
    }

    private static String utf8OrNull(byte[] bytes, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** 读取进程输出并解码；读取失败返回空串，不影响主流程。 */
    public static String read(InputStream stream) {
        try (stream) {
            return decode(stream.readAllBytes());
        } catch (IOException e) {
            return "";
        }
    }
}
