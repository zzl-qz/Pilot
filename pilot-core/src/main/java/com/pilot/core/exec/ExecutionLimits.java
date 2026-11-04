package com.pilot.core.exec;

/**
 * 单次命令的资源上限。
 *
 * <p>LOCAL 后端只能落实超时与输出上限；内存、CPU、进程数这些必须由容器或操作系统强制。
 * 记录在这里是为了让两种后端使用同一套参数，而不是在调用点各写一份。
 *
 * @param timeoutSeconds 超时；<=0 表示不限制（不建议）
 * @param maxOutputBytes 捕获的输出上限
 * @param memoryMb       容器内存上限
 * @param cpus           容器 CPU 配额
 * @param pidsLimit      容器进程数上限
 */
public record ExecutionLimits(long timeoutSeconds, int maxOutputBytes, int memoryMb, double cpus, int pidsLimit) {
    public ExecutionLimits {
        if (maxOutputBytes < 1_024) {
            throw new IllegalArgumentException("maxOutputBytes must be >= 1024");
        }
        if (memoryMb < 64) {
            throw new IllegalArgumentException("memoryMb must be >= 64");
        }
        if (cpus <= 0) {
            throw new IllegalArgumentException("cpus must be > 0");
        }
        if (pidsLimit < 8) {
            throw new IllegalArgumentException("pidsLimit must be >= 8");
        }
    }

    public static ExecutionLimits defaults() {
        return new ExecutionLimits(600, 200_000, 2_048, 2.0, 512);
    }

    public ExecutionLimits withTimeoutSeconds(long seconds) {
        return new ExecutionLimits(seconds, maxOutputBytes, memoryMb, cpus, pidsLimit);
    }
}
