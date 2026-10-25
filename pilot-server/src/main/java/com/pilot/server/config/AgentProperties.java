package com.pilot.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Agent 运行配置。
 *
 * @param mode                   mock：离线 Mock 剧本；real：真实模型 + 只读工具
 * @param workspace              工作区根目录，所有工具只能访问其内部
 * @param maxSteps               单次运行最大模型调用轮数
 * @param repeatedToolCallLimit  连续相同工具调用达到该次数即中断
 */
@ConfigurationProperties(prefix = "pilot.agent")
public record AgentProperties(String mode, String workspace, int maxSteps, int repeatedToolCallLimit) {
    public AgentProperties {
        mode = mode == null || mode.isBlank() ? "mock" : mode.strip();
        workspace = workspace == null || workspace.isBlank()
                ? Path.of("").toAbsolutePath().normalize().toString()
                : Path.of(workspace).toAbsolutePath().normalize().toString();
        maxSteps = maxSteps < 1 ? 8 : maxSteps;
        repeatedToolCallLimit = repeatedToolCallLimit < 1 ? 3 : repeatedToolCallLimit;
    }
}
