package com.pilot.core.runtime.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs bootstrap commands with bounded output, timeouts, and child-process cleanup. */
final class BootstrapProcess {

    private BootstrapProcess() {
    }

    record Execution(int code, String output) {
    }

    static void run(List<String> command, Path directory, Map<String, String> env, Duration timeout) throws Exception {
        if (execute(command, directory, env, timeout, true).code() != 0) {
            throw new IOException("环境准备命令失败，请查看上方构建输出；未继续启动应用");
        }
    }

    static Execution execute(List<String> command, Path directory, Map<String, String> env, Duration timeout,
        boolean inherit) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        if (inherit) {
            builder.inheritIO();
        }
        Process child = builder.start();
        if (!inherit) {
            child.getOutputStream().close();
        }
        Thread cleanup = new Thread(() -> {
            child.descendants().forEach(ProcessHandle::destroy);
            child.destroy();
        });
        Runtime.getRuntime().addShutdownHook(cleanup);
        var output = new ByteArrayOutputStream();
        Thread reader = inherit ? null : Thread.startVirtualThread(() -> {
            try (var input = child.getInputStream()) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    synchronized (output) {
                        if (output.size() + count <= 4 * 1024 * 1024) {
                            output.write(bytes, 0, count);
                        }
                    }
                }
            } catch (IOException e) {
                // 进程取消或超时会关闭管道。
            }
        });
        try {
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("环境准备命令超时");
            }
            if (reader != null) {
                reader.join(1000);
            }
            synchronized (output) {
                return new Execution(child.exitValue(), output.toString(StandardCharsets.UTF_8));
            }
        } finally {
            child.descendants().forEach(ProcessHandle::destroy);
            child.destroyForcibly();
            if (reader != null) {
                reader.interrupt();
            }
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException e) {
                // JVM 正在退出，由已经注册的钩子完成本进程的子进程清理。
            }
        }
    }
}
