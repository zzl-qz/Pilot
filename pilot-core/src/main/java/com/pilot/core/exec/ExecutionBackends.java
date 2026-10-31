package com.pilot.core.exec;

/**
 * 按配置选择执行后端。
 *
 * <p>关键约定：请求容器隔离但容器不可用时，<strong>不</strong>静默退回普通进程。
 * 返回的 {@link Selection#degraded()} 为 true 且给出原因，调用方必须把它展示出来，
 * 否则用户会以为自己得到了隔离。
 */
public final class ExecutionBackends {
    private ExecutionBackends() {
    }

    /**
     * @param backend 实际使用的后端
     * @param requested 配置请求的隔离级别
     * @param degraded  请求的隔离没有落实
     * @param reason    降级原因；未降级时为空串
     */
    public record Selection(ExecutionBackend backend, ExecutionIsolation requested, boolean degraded,
                            String reason, ExecutionSettings settings) {
        public Selection {
            settings = settings == null ? ExecutionSettings.local() : settings;
        }

        public boolean isolated() {
            return backend.isolation() == ExecutionIsolation.CONTAINER;
        }

        public String describe() {
            return isolated()
                    ? "已启用容器隔离（" + backend.id() + "）"
                    : "未启用系统级隔离：" + (degraded ? reason : "当前配置为本地执行");
        }
    }

    public static Selection select(ExecutionSettings settings) {
        ExecutionSettings effective = settings == null ? ExecutionSettings.local() : settings;
        if (effective.isolation() != ExecutionIsolation.CONTAINER) {
            return new Selection(new LocalExecutionBackend(EnvironmentSanitizer.defaults()),
                    ExecutionIsolation.LOCAL, false, "", effective);
        }
        ContainerExecutionBackend container = new ContainerExecutionBackend(
                effective.containerRuntime(), effective.image(), "/workspace", "");
        if (container.available()) {
            return new Selection(container, ExecutionIsolation.CONTAINER, false, "", effective);
        }
        return new Selection(new LocalExecutionBackend(EnvironmentSanitizer.defaults()),
                ExecutionIsolation.CONTAINER, true, container.unavailableReason(), effective);
    }
}
