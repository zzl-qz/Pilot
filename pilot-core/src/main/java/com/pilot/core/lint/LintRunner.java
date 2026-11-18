package com.pilot.core.lint;

import java.nio.file.Path;

/** 在工作区根目录执行已经拼好的 lint 命令。测试用假实现，正式运行用进程。 */
public interface LintRunner {
    LintResult run(Path workspace, String command, int timeoutSeconds);

    record LintResult(int exitCode, String output, boolean timedOut, boolean started) {
        public LintResult {
            output = output == null ? "" : output;
        }

        public static LintResult failedToStart(String message) {
            return new LintResult(-1, message == null ? "" : message, false, false);
        }
    }
}
