package com.pilot.server.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.pilot.core.permission.PermissionMode;

/**
 * Agent 运行配置。
 *
 * @param workspace             工作区根目录，所有工具只能访问其内部
 * @param permissionMode        READ_ONLY / ASK / AUTO_EDIT / AUTO；默认 AUTO
 * @param maxSteps              单次运行最大模型调用轮数
 * @param repeatRemindAt        相同工具调用连续无新结果达到该次数时注入提醒；不填默认 3，配 0 关闭提醒
 * @param repeatAbortAt         兼容旧配置的阈值；不填默认 5，重复调用不再据此中断运行
 * @param approvalTimeoutSeconds 审批等待超时（秒），超时按拒绝处理
 * @param runThreads            同时执行的 run 数，单个 run 卡住时不影响其他会话
 * @param maxPlanContinues      兼容旧配置，默认 0；计划未完成不再自动催模型继续
 * @param contextMaxChars       上下文预算（字符估算）。太小会频繁压缩、丢细节，任务做得浅
 * @param completionChecks      通用收尾提醒门，默认关闭；不决定 Direct 的验证状态，也不关闭 Goal 独立核验
 * @param directMaxRequests     普通执行模型请求的高位事故熔断；不用它判断任务是否完成
 * @param directMaxInputTokens  普通执行累计输入的高位事故熔断
 * @param directMaxOutputTokens 普通执行累计输出的高位事故熔断
 * @param directMaxActiveMillis 普通执行模型活跃时间的高位事故熔断
 * @param directMaxInvestigationTools 兼容旧配置的高位调查诊断值；运行时不据此拒绝读取/搜索
 */
@ConfigurationProperties(prefix = "pilot.agent")
public record AgentProperties(String workspace, PermissionMode permissionMode, int maxSteps, Integer repeatRemindAt,
    Integer repeatAbortAt, int approvalTimeoutSeconds, int runThreads, Integer maxPlanContinues,
    Integer contextMaxChars, Boolean completionChecks, Boolean goalModeEnabled, Integer directMaxRequests,
    Long directMaxInputTokens, Long directMaxOutputTokens, Long directMaxActiveMillis,
    Integer directMaxInvestigationTools, Long goalMaxInputTokens, Long goalMaxOutputTokens, Long goalMaxActiveMillis) {
    public AgentProperties(String workspace, PermissionMode mode, int steps, Integer remind, Integer abort, int timeout,
        int threads, Integer continues, Integer chars, Boolean checks) {
        this(workspace, mode, steps, remind, abort, timeout, threads, continues, chars, checks, false, null, null, null,
            null, null, null, null, null);
    }

    public AgentProperties(String workspace, PermissionMode mode, int steps, Integer remind, Integer abort, int timeout,
        int threads, Integer continues, Integer chars, Boolean checks, Boolean goals) {
        this(workspace, mode, steps, remind, abort, timeout, threads, continues, chars, checks, goals, null, null, null,
            null, null, null, null, null);
    }

    public boolean goalModeEnabledValue() {
        return !Boolean.FALSE.equals(goalModeEnabled);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AgentProperties {
        workspace = workspace == null || workspace.isBlank() ? Path.of("").toAbsolutePath().normalize().toString()
            : Path.of(workspace).toAbsolutePath().normalize().toString();
        // Pilot 默认 AUTO；READ_ONLY、ASK、AUTO_EDIT 可按任务需要配置，工作区边界仍生效。
        permissionMode = permissionMode == null ? PermissionMode.AUTO : permissionMode;
        maxSteps = maxSteps < 1 ? 1_000 : maxSteps;
        int abort = repeatAbortAt == null || repeatAbortAt < 1 ? 5 : repeatAbortAt;
        // 提醒档必须早于中断档：配反了启动就报错，不静默修正
        int remind = repeatRemindAt == null ? 3 : repeatRemindAt;
        if (remind < 0 || (remind > 0 && remind >= abort)) {
            throw new IllegalArgumentException(
                "pilot.agent.repeat-remind-at 必须为 0（关闭提醒）或小于 repeat-abort-at（当前 " + abort + "）");
        }
        repeatRemindAt = remind;
        repeatAbortAt = abort;
        approvalTimeoutSeconds = approvalTimeoutSeconds < 1 ? 300 : approvalTimeoutSeconds;
        runThreads = runThreads < 1 ? 4 : Math.min(runThreads, 16);
        // 计划不再拦住收尾：默认不自动继续，只把未完成项如实报给用户。
        int continues = maxPlanContinues == null ? 0 : maxPlanContinues;
        maxPlanContinues = continues < 0 ? 0 : Math.min(continues, 10);
        contextMaxChars =
            contextMaxChars == null || contextMaxChars < 20_000 ? 400_000 : Math.min(contextMaxChars, 2_000_000);
        // 通用收尾提醒门默认关闭；Direct 收尾仍按实际证据区分检查通过、失败、陈旧或未验证。
        // Goal 的独立核验不受此开关影响；执行结束本身不代表已核验。
        // 可配置 pilot.agent.completion-checks；仅未显式配置时才回退读取 -Dpilot.completion.checks。
        String flag = System.getProperty("pilot.completion.checks");
        if (completionChecks == null) {
            completionChecks = flag != null && !flag.isBlank() && "true".equalsIgnoreCase(flag.strip());
        }
        directMaxRequests =
            directMaxRequests == null || directMaxRequests < 4 ? maxSteps : Math.min(directMaxRequests, maxSteps);
        directMaxInputTokens =
            directMaxInputTokens == null || directMaxInputTokens < 50_000 ? 50_000_000L : directMaxInputTokens;
        directMaxOutputTokens =
            directMaxOutputTokens == null || directMaxOutputTokens < 5_000 ? 2_000_000L : directMaxOutputTokens;
        directMaxActiveMillis =
            directMaxActiveMillis == null || directMaxActiveMillis < 60_000 ? 8 * 60 * 60_000L : directMaxActiveMillis;
        directMaxInvestigationTools =
            directMaxInvestigationTools == null || directMaxInvestigationTools < 8 ? 512 : directMaxInvestigationTools;
        goalMaxInputTokens =
            goalMaxInputTokens == null || goalMaxInputTokens < 10_000 ? 50_000_000L : goalMaxInputTokens;
        goalMaxOutputTokens =
            goalMaxOutputTokens == null || goalMaxOutputTokens < 1_000 ? 2_000_000L : goalMaxOutputTokens;
        goalMaxActiveMillis =
            goalMaxActiveMillis == null || goalMaxActiveMillis < 60_000 ? 8 * 60 * 60_000L : goalMaxActiveMillis;
    }

    /** 通用收尾提醒门是否启用（默认关闭）；与实际验证状态、Goal 独立核验分别处理。 */
    public boolean completionChecksEnabled() {
        return Boolean.TRUE.equals(completionChecks);
    }
}
