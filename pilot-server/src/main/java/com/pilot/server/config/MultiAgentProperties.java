package com.pilot.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 多智能体调度配置。
 *
 * @param enabled              是否注册 {@code dispatch_agents} 工具
 * @param maxParallel          单个依赖层内并行执行的子 Agent 上限
 * @param maxAttempts          单个子任务最多执行次数（含首次），2 次重试即 3
 * @param maxSteps             子 Agent 单次运行的模型轮数上限
 * @param verifyTimeoutSeconds 硬校验命令超时
 * @param verifyCommands       合并后执行的校验命令；为空时按项目类型自动推断
 */
@ConfigurationProperties(prefix = "pilot.multi")
public record MultiAgentProperties(Boolean enabled, Integer maxParallel, Integer maxAttempts,
                                   Integer maxSteps, Integer verifyTimeoutSeconds,
                                   List<String> verifyCommands) {
    public MultiAgentProperties {
        enabled = enabled == null || enabled;
        maxParallel = maxParallel == null || maxParallel < 1 ? 2 : Math.min(maxParallel, 6);
        maxAttempts = maxAttempts == null || maxAttempts < 1 ? 3 : Math.min(maxAttempts, 5);
        maxSteps = maxSteps == null || maxSteps < 5 ? 40 : Math.min(maxSteps, 200);
        verifyTimeoutSeconds = verifyTimeoutSeconds == null || verifyTimeoutSeconds < 10
                ? 600
                : Math.min(verifyTimeoutSeconds, 3_600);
        verifyCommands = verifyCommands == null ? List.of() : verifyCommands.stream()
                .filter(command -> command != null && !command.isBlank())
                .map(String::strip)
                .toList();
    }
}
