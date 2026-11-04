package com.pilot.core.exec;

/**
 * 受控命令的执行结果。
 *
 * @param exitCode   退出码；无法启动时为 {@code -1}
 * @param output     合并后的标准输出与错误输出（已按上限截断）
 * @param timedOut   是否因为超时被终止
 * @param truncated  输出是否被截断
 * @param backend    实际使用的后端 id
 * @param isolation  实际使用的隔离级别
 * @param error      启动失败等原因；正常执行时为空串
 */
public record ExecutionResult(int exitCode, String output, boolean timedOut, boolean truncated, String backend,
    ExecutionIsolation isolation, String error) {

    public ExecutionResult {
        output = output == null ? "" : output;
        backend = backend == null ? "" : backend;
        isolation = isolation == null ? ExecutionIsolation.LOCAL : isolation;
        error = error == null ? "" : error;
    }

    public boolean ok() {
        return error.isEmpty() && !timedOut && exitCode == 0;
    }

    public static ExecutionResult failure(String backend, ExecutionIsolation isolation, String error) {
        return new ExecutionResult(-1, "", false, false, backend, isolation, error);
    }
}
