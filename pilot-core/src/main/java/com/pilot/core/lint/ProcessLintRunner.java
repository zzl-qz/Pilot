package com.pilot.core.lint;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 用系统 shell 跑 lint。命令来自仓库自己的清单，工作目录锁在该仓库根。
 * Windows 用 cmd.exe，其他系统用 sh，避免把路径写死成某一个平台。
 */
public final class ProcessLintRunner implements LintRunner {
    public static final ProcessLintRunner INSTANCE = new ProcessLintRunner();
    private static final int MAX_OUTPUT_CHARS = 16_000;

    private ProcessLintRunner() {
    }

    @Override
    public LintResult run(Path workspace, String command, int timeoutSeconds) {
        if (workspace == null || command == null || command.isBlank()) {
            return LintResult.failedToStart("没有可执行的 lint 命令");
        }
        Process process;
        try {
            process =
                new ProcessBuilder(shell(command)).directory(workspace.toFile()).redirectErrorStream(true).start();
        } catch (Exception e) {
            return LintResult.failedToStart(e.getMessage() == null ? "无法启动 lint" : e.getMessage());
        }
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> drain(process.getInputStream(), output), "lint-output");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1_000);
                return new LintResult(-1, clip(output.toString()), true, true);
            }
            reader.join(1_000);
            return new LintResult(process.exitValue(), clip(output.toString()), false, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return LintResult.failedToStart("lint 被中断");
        }
    }

    static List<String> shell(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        return List.of("sh", "-c", command);
    }

    private static void drain(InputStream input, StringBuilder output) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) >= 0 && output.length() < MAX_OUTPUT_CHARS) {
                output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 进程被超时杀掉时读端会断，输出保留已经拿到的部分
        }
    }

    private static String clip(String text) {
        String normalized = text.replace("\r\n", "\n").strip();
        return normalized.length() <= MAX_OUTPUT_CHARS ? normalized : normalized.substring(0, MAX_OUTPUT_CHARS);
    }
}
