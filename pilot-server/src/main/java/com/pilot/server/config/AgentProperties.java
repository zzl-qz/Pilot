package com.pilot.server.config;

import com.pilot.core.permission.PermissionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Agent 运行配置。
 *
 * @param mode                  mock：离线 Mock 剧本；real：真实模型 + 工具
 * @param workspace             工作区根目录，所有工具只能访问其内部
 * @param permissionMode        READ_ONLY / ASK / AUTO
 * @param maxSteps              单次运行最大模型调用轮数
 * @param repeatedToolCallLimit 连续相同工具调用达到该次数即中断
 * @param approvalTimeoutSeconds 审批等待超时（秒），超时按拒绝处理
 * @param runThreads            同时执行的 run 数，单个 run 卡住时不影响其他会话
 */
@ConfigurationProperties(prefix = "pilot.agent")
public record AgentProperties(String mode, String workspace, PermissionMode permissionMode,
                              int maxSteps, int repeatedToolCallLimit, int approvalTimeoutSeconds,
                              int runThreads) {
    public AgentProperties {
        mode = mode == null || mode.isBlank() ? "mock" : mode.strip();
        workspace = workspace == null || workspace.isBlank()
                ? Path.of("").toAbsolutePath().normalize().toString()
                : Path.of(workspace).toAbsolutePath().normalize().toString();
        permissionMode = permissionMode == null ? PermissionMode.READ_ONLY : permissionMode;
        maxSteps = maxSteps < 1 ? 120 : maxSteps;
        repeatedToolCallLimit = repeatedToolCallLimit < 1 ? 3 : repeatedToolCallLimit;
        approvalTimeoutSeconds = approvalTimeoutSeconds < 1 ? 300 : approvalTimeoutSeconds;
        runThreads = runThreads < 1 ? 4 : Math.min(runThreads, 16);
    }
}
