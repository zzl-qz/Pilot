package com.pilot.server.config;

import com.pilot.core.permission.PermissionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Agent 运行配置。
 *
 * @param workspace             工作区根目录，所有工具只能访问其内部
 * @param permissionMode        READ_ONLY / ASK / AUTO
 * @param maxSteps              单次运行最大模型调用轮数
 * @param repeatedToolCallLimit 连续相同工具调用达到该次数即中断
 * @param approvalTimeoutSeconds 审批等待超时（秒），超时按拒绝处理
 * @param runThreads            同时执行的 run 数，单个 run 卡住时不影响其他会话
 * @param maxPlanContinues      模型提前收尾但计划未完成时，自动催它继续的最大次数；不填默认 8，0 表示不自动继续
 * @param contextMaxChars       上下文预算（字符估算）。太小会频繁压缩、丢细节，任务做得浅
 * @param completionChecks      收尾门：改了代码却没验证、有改动却没核对目标时是否推回一轮
 */
@ConfigurationProperties(prefix = "pilot.agent")
public record AgentProperties(String workspace, PermissionMode permissionMode,
                              int maxSteps, int repeatedToolCallLimit, int approvalTimeoutSeconds,
                              int runThreads, Integer maxPlanContinues, Integer contextMaxChars,
                              Boolean completionChecks) {
    public AgentProperties {
        workspace = workspace == null || workspace.isBlank()
                ? Path.of("").toAbsolutePath().normalize().toString()
                : Path.of(workspace).toAbsolutePath().normalize().toString();
        permissionMode = permissionMode == null ? PermissionMode.READ_ONLY : permissionMode;
        maxSteps = maxSteps < 1 ? 120 : maxSteps;
        repeatedToolCallLimit = repeatedToolCallLimit < 1 ? 3 : repeatedToolCallLimit;
        approvalTimeoutSeconds = approvalTimeoutSeconds < 1 ? 300 : approvalTimeoutSeconds;
        runThreads = runThreads < 1 ? 4 : Math.min(runThreads, 16);
        int continues = maxPlanContinues == null ? 8 : maxPlanContinues;
        maxPlanContinues = continues < 0 ? 0 : Math.min(continues, 10);
        contextMaxChars = contextMaxChars == null || contextMaxChars < 20_000
                ? 120_000
                : Math.min(contextMaxChars, 2_000_000);
        // 系统属性作为兜底，保留 `-Dpilot.completion.checks=false` 的老用法
        String flag = System.getProperty("pilot.completion.checks");
        if (completionChecks == null) {
            completionChecks = flag == null || flag.isBlank() || !"false".equalsIgnoreCase(flag.strip());
        }
    }

    /** 收尾门是否启用（{@code null} 视为启用）。 */
    public boolean completionChecksEnabled() {
        return completionChecks == null || completionChecks;
    }
}
