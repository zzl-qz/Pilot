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
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_MEMORY_MB = 2_048;
    public static final double DEFAULT_CPUS = 2.0;
    public static final int DEFAULT_PIDS = 512;
    public static final int MIN_MEMORY_MB = 64;
    public static final int MIN_PIDS = 8;

    public ExecutionLimits {
        if (maxOutputBytes < 1_024) {
            throw new IllegalArgumentException("maxOutputBytes must be >= 1024");
        }
        if (memoryMb < MIN_MEMORY_MB) {
            throw new IllegalArgumentException("memoryMb must be >= " + MIN_MEMORY_MB);
        }
        if (cpus <= 0) {
            throw new IllegalArgumentException("cpus must be > 0");
        }
        if (pidsLimit < MIN_PIDS) {
            throw new IllegalArgumentException("pidsLimit must be >= " + MIN_PIDS);
        }
    }

    public static ExecutionLimits defaults() {
        return new ExecutionLimits(DEFAULT_TIMEOUT_SECONDS, 200_000, DEFAULT_MEMORY_MB, DEFAULT_CPUS, DEFAULT_PIDS);
    }

    public ExecutionLimits withTimeoutSeconds(long seconds) {
        return new ExecutionLimits(seconds, maxOutputBytes, memoryMb, cpus, pidsLimit);
    }
}
