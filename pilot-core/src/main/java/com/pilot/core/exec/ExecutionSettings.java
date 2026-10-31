package com.pilot.core.exec;

/**
 * 执行隔离配置。
 *
 * @param isolation        期望的隔离级别；容器不可用时不会静默降级，而是显式报告
 * @param image            容器镜像，需包含项目构建所需的工具链
 * @param containerRuntime docker / podman；为空时自动探测
 * @param network          容器内是否允许访问网络；默认关闭
 */
public record ExecutionSettings(ExecutionIsolation isolation, String image, String containerRuntime,
                                boolean network, ExecutionLimits limits) {
    public ExecutionSettings {
        isolation = isolation == null ? ExecutionIsolation.LOCAL : isolation;
        image = image == null ? "" : image.strip();
        containerRuntime = containerRuntime == null ? "" : containerRuntime.strip();
        limits = limits == null ? ExecutionLimits.defaults() : limits;
    }

    public static ExecutionSettings local() {
        return new ExecutionSettings(ExecutionIsolation.LOCAL, "", "", false, ExecutionLimits.defaults());
    }

    /** 读取系统属性（{@code pilot.execution.*}）覆盖，便于本机与 CI 切换。 */
    public static ExecutionSettings fromSystemProperties() {
        String isolation = System.getProperty("pilot.execution.isolation", "local");
        return new ExecutionSettings(
                ExecutionIsolation.parse(isolation),
                System.getProperty("pilot.execution.image", ""),
                System.getProperty("pilot.execution.runtime", ""),
                Boolean.parseBoolean(System.getProperty("pilot.execution.network", "false")),
                ExecutionLimits.defaults());
    }

    public ExecutionSettings withNetwork(boolean allowed) {
        return new ExecutionSettings(isolation, image, containerRuntime, allowed, limits);
    }

    public ExecutionSettings withLimits(ExecutionLimits newLimits) {
        return new ExecutionSettings(isolation, image, containerRuntime, network, newLimits);
    }
}
