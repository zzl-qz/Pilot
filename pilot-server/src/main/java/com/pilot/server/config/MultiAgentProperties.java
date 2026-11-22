package com.pilot.server.config;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.pilot.core.exec.ExecutionLimits;

/**
 * 多智能体调度配置。
 *
 * @param enabled              是否注册 {@code dispatch_agents} 工具
 * @param maxParallel          单个依赖层内并行执行的子 Agent 上限
 * @param maxAttempts          单个子任务最多执行次数（含首次），2 次重试即 3
 * @param maxSteps             子 Agent 单次运行的模型轮数上限
 * @param verifyTimeoutSeconds 硬校验命令超时
 * @param verifyCommands       合并后执行的校验命令；为空时按项目类型自动推断
 * @param crossSessionConflict 跨会话冲突策略：{@code off} 不查、{@code warn} 只写报告、{@code block} 交人工仲裁
 */
@ConfigurationProperties(prefix = "pilot.multi")
public record MultiAgentProperties(Boolean enabled, Integer maxParallel, Integer maxAttempts, Integer maxSteps,
    Integer verifyTimeoutSeconds, List<String> verifyCommands, String crossSessionConflict,
    Integer crossSessionWindowMinutes) {
    public static final int DEFAULT_CROSS_SESSION_WINDOW_MINUTES = 240;
    public static final String CROSS_SESSION_OFF = "off";
    public static final String CROSS_SESSION_WARN = "warn";
    public static final String CROSS_SESSION_BLOCK = "block";

    public MultiAgentProperties {
        enabled = enabled == null || enabled;
        maxParallel = maxParallel == null || maxParallel < 1 ? 2 : Math.min(maxParallel, 6);
        maxAttempts = maxAttempts == null || maxAttempts < 1 ? 3 : Math.min(maxAttempts, 5);
        maxSteps = maxSteps == null || maxSteps < 5 ? 40 : Math.min(maxSteps, 200);
        verifyTimeoutSeconds = verifyTimeoutSeconds == null || verifyTimeoutSeconds < 10
            ? ExecutionLimits.DEFAULT_TIMEOUT_SECONDS : Math.min(verifyTimeoutSeconds, 3_600);
        verifyCommands = verifyCommands == null ? List.of() : verifyCommands.stream()
            .filter(command -> command != null && !command.isBlank()).map(String::strip).toList();
        crossSessionConflict = normalizeCrossSession(crossSessionConflict);
        crossSessionWindowMinutes = crossSessionWindowMinutes == null || crossSessionWindowMinutes < 1
            ? DEFAULT_CROSS_SESSION_WINDOW_MINUTES : Math.min(crossSessionWindowMinutes, 10_080);
    }

    public MultiAgentProperties(Boolean enabled, Integer maxParallel, Integer maxAttempts, Integer maxSteps,
        Integer verifyTimeoutSeconds, List<String> verifyCommands, String crossSessionConflict) {
        this(enabled, maxParallel, maxAttempts, maxSteps, verifyTimeoutSeconds, verifyCommands, crossSessionConflict,
            null);
    }

    /** 未配置跨会话策略时的默认值：只写报告，不阻塞正常并行。 */
    public MultiAgentProperties(Boolean enabled, Integer maxParallel, Integer maxAttempts, Integer maxSteps,
        Integer verifyTimeoutSeconds, List<String> verifyCommands) {
        this(enabled, maxParallel, maxAttempts, maxSteps, verifyTimeoutSeconds, verifyCommands, null, null);
    }

    private static String normalizeCrossSession(String value) {
        if (value == null || value.isBlank()) {
            return CROSS_SESSION_WARN;
        }
        String mode = value.strip().toLowerCase(Locale.ROOT);
        return CROSS_SESSION_OFF.equals(mode) || CROSS_SESSION_BLOCK.equals(mode) ? mode : CROSS_SESSION_WARN;
    }
}
