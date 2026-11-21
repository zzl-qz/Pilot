package com.pilot.server.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.pilot.core.lint.LintGate;
import com.pilot.core.lint.ProcessLintRunner;
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
 * @param maxPlanContinues      兼容旧配置，默认 0；保留兼容字段；启用完成检查时最多提醒两次
 * @param contextMaxChars       上下文预算（字符估算）。太小会频繁压缩、丢细节，任务做得浅
 * @param completionChecks      通用收尾检查门，默认开启；不决定 Direct 的验证状态，也不关闭 Goal 独立核验
 * @param directMaxRequests     单个普通任务的模型请求收敛上限（默认 500，对齐 OpenHands）；继续执行开新一段额度，不用它判断任务是否完成
 * @param directMaxInputTokens  普通执行累计输入的高位事故熔断
 * @param directMaxOutputTokens 普通执行累计输出的高位事故熔断
 * @param directMaxActiveMillis 普通执行模型活跃时间的高位事故熔断
 * @param directMaxInvestigationTools 兼容旧配置的高位调查诊断值；运行时不据此拒绝读取/搜索
 * @param lintGateEnabled      收尾时是否运行当前仓库的 lint。默认开启，设为 false 可做对照
 * @param lintOnFailure        规范没过时的处理：retry 先改，次数用尽且测试通过才放行（默认）；pass 直接记录并放行；stop 直接终止
 * @param preflightLocalizationEnabled 是否在每次新用户消息进入循环前执行确定性候选目标预定位；默认开启，设为 false 可做对照
 * @param repositoryBrief      会话首条 run 注入的仓库快照档位：full 带目录树（默认）；brief 只留分支、脏文件数、构建与 lint；off 不注入
 */
@ConfigurationProperties(prefix = "pilot.agent")
public record AgentProperties(String workspace, PermissionMode permissionMode, int maxSteps, Integer repeatRemindAt,
    Integer repeatAbortAt, int approvalTimeoutSeconds, int runThreads, Integer maxPlanContinues,
    Integer contextMaxChars, Boolean completionChecks, Boolean goalModeEnabled, Integer directMaxRequests,
    Long directMaxInputTokens, Long directMaxOutputTokens, Long directMaxActiveMillis,
    Integer directMaxInvestigationTools, Long goalMaxInputTokens, Long goalMaxOutputTokens, Long goalMaxActiveMillis,
    Boolean lintGateEnabled, String lintOnFailure, Boolean preflightLocalizationEnabled, String repositoryBrief) {
    private static final Set<String> REPOSITORY_BRIEF_MODES = Set.of("full", "brief", "off");
    private static final int DEFAULT_MAX_STEPS = 1_000;
    /** 单个普通任务的模型请求收敛上限；对齐 OpenHands 的 max_iterations 默认值 500，继续执行会开新一段额度。 */
    private static final int DEFAULT_DIRECT_MAX_REQUESTS = 500;
    private static final long DEFAULT_MAX_INPUT_TOKENS = 50_000_000L;
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 2_000_000L;
    private static final long DEFAULT_MAX_ACTIVE_MILLIS = 8 * 60 * 60_000L;

    public AgentProperties(String workspace, PermissionMode mode, int steps, Integer remind, Integer abort, int timeout,
        int threads, Integer continues, Integer chars, Boolean checks) {
        this(workspace, mode, steps, remind, abort, timeout, threads, continues, chars, checks, false, null, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    public AgentProperties(String workspace, PermissionMode mode, int steps, Integer remind, Integer abort, int timeout,
        int threads, Integer continues, Integer chars, Boolean checks, Boolean goals) {
        this(workspace, mode, steps, remind, abort, timeout, threads, continues, chars, checks, goals, null, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    public AgentProperties(String workspace, PermissionMode mode, int steps, Integer remind, Integer abort, int timeout,
        int threads, Integer continues, Integer chars, Boolean checks, Boolean goals, Integer directMaxRequests,
        Long directMaxInputTokens, Long directMaxOutputTokens, Long directMaxActiveMillis,
        Integer directMaxInvestigationTools, Long goalMaxInputTokens, Long goalMaxOutputTokens,
        Long goalMaxActiveMillis, Boolean lintGateEnabled, String lintOnFailure) {
        this(workspace, mode, steps, remind, abort, timeout, threads, continues, chars, checks, goals,
            directMaxRequests, directMaxInputTokens, directMaxOutputTokens, directMaxActiveMillis,
            directMaxInvestigationTools, goalMaxInputTokens, goalMaxOutputTokens, goalMaxActiveMillis, lintGateEnabled,
            lintOnFailure, null, null);
    }

    /** 显式指定仓库快照档位的装配入口；其余字段照旧走默认值。 */
    public AgentProperties(String workspace, PermissionMode mode, int steps, Integer remind, Integer abort, int timeout,
        int threads, Integer continues, Integer chars, Boolean checks, Boolean goals, Integer directMaxRequests,
        Long directMaxInputTokens, Long directMaxOutputTokens, Long directMaxActiveMillis,
        Integer directMaxInvestigationTools, Long goalMaxInputTokens, Long goalMaxOutputTokens,
        Long goalMaxActiveMillis, Boolean lintGateEnabled, String lintOnFailure, String repositoryBrief) {
        this(workspace, mode, steps, remind, abort, timeout, threads, continues, chars, checks, goals,
            directMaxRequests, directMaxInputTokens, directMaxOutputTokens, directMaxActiveMillis,
            directMaxInvestigationTools, goalMaxInputTokens, goalMaxOutputTokens, goalMaxActiveMillis, lintGateEnabled,
            lintOnFailure, null, repositoryBrief);
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
        maxSteps = maxSteps < 1 ? DEFAULT_MAX_STEPS : maxSteps;
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
        // 旧字段仅为兼容配置保留；启用完成检查后固定最多提醒两次。
        int continues = maxPlanContinues == null ? 0 : maxPlanContinues;
        maxPlanContinues = continues < 0 ? 0 : Math.min(continues, 10);
        contextMaxChars =
            contextMaxChars == null || contextMaxChars < 20_000 ? 400_000 : Math.min(contextMaxChars, 2_000_000);
        // 通用收尾检查门默认开启；Direct 收尾仍按实际证据区分检查通过、失败、陈旧或未验证。
        // Goal 的独立核验不受此开关影响；执行结束本身不代表已核验。
        // 可配置 pilot.agent.completion-checks；仅未显式配置时才回退读取 -Dpilot.completion.checks。
        if (completionChecks == null) {
            String flag = System.getProperty("pilot.completion.checks");
            completionChecks = flag == null || flag.isBlank() || "true".equalsIgnoreCase(flag.strip());
        }
        int directRequests = directMaxRequests == null ? DEFAULT_DIRECT_MAX_REQUESTS : directMaxRequests;
        directMaxRequests = directRequests < 4 ? maxSteps : Math.min(directRequests, maxSteps);
        directMaxInputTokens = directMaxInputTokens == null || directMaxInputTokens < 50_000 ? DEFAULT_MAX_INPUT_TOKENS
            : directMaxInputTokens;
        directMaxOutputTokens = directMaxOutputTokens == null || directMaxOutputTokens < 5_000
            ? DEFAULT_MAX_OUTPUT_TOKENS : directMaxOutputTokens;
        directMaxActiveMillis = directMaxActiveMillis == null || directMaxActiveMillis < 60_000
            ? DEFAULT_MAX_ACTIVE_MILLIS : directMaxActiveMillis;
        directMaxInvestigationTools =
            directMaxInvestigationTools == null || directMaxInvestigationTools < 8 ? 512 : directMaxInvestigationTools;
        goalMaxInputTokens =
            goalMaxInputTokens == null || goalMaxInputTokens < 10_000 ? DEFAULT_MAX_INPUT_TOKENS : goalMaxInputTokens;
        goalMaxOutputTokens = goalMaxOutputTokens == null || goalMaxOutputTokens < 1_000 ? DEFAULT_MAX_OUTPUT_TOKENS
            : goalMaxOutputTokens;
        goalMaxActiveMillis = goalMaxActiveMillis == null || goalMaxActiveMillis < 60_000 ? DEFAULT_MAX_ACTIVE_MILLIS
            : goalMaxActiveMillis;
        // 默认开启。显式 false 才关闭，方便同一套样例做开/关对照。
        if (lintGateEnabled == null) {
            String flag = System.getProperty("pilot.agent.lint-gate-enabled");
            lintGateEnabled = flag == null || flag.isBlank() || !"false".equalsIgnoreCase(flag.strip());
        }
        if (lintOnFailure == null || lintOnFailure.isBlank()) {
            String policy = System.getProperty("pilot.agent.lint-on-failure");
            lintOnFailure = policy == null || policy.isBlank() ? "retry" : policy.strip();
        }
        if (preflightLocalizationEnabled == null) {
            String flag = System.getProperty("pilot.agent.preflight-localization-enabled");
            preflightLocalizationEnabled = flag == null || flag.isBlank() || !"false".equalsIgnoreCase(flag.strip());
        }
        // 仓库快照档位：写错就启动失败，不静默当成 full 把目录树塞给模型。
        repositoryBrief = repositoryBrief == null || repositoryBrief.isBlank() ? "full"
            : repositoryBrief.strip().toLowerCase(Locale.ROOT);
        if (!REPOSITORY_BRIEF_MODES.contains(repositoryBrief)) {
            throw new IllegalArgumentException(
                "pilot.agent.repository-brief 只能是 full、brief 或 off（当前 " + repositoryBrief + "）");
        }
    }

    /** 仓库快照是否注入。off 时首条 run 也不带快照。 */
    public boolean repositoryBriefEnabled() {
        return !"off".equals(repositoryBrief);
    }

    /** full 带目录树；brief 只留分支、脏文件数、构建与 lint。 */
    public boolean repositoryBriefDetailed() {
        return "full".equals(repositoryBrief);
    }

    /** 确定性 Pre-flight 候选目标预定位默认开启。 */
    public boolean preflightLocalizationEnabledValue() {
        return !Boolean.FALSE.equals(preflightLocalizationEnabled);
    }

    /** 收尾 lint 默认开启。 */
    public boolean lintGateEnabledValue() {
        return !Boolean.FALSE.equals(lintGateEnabled);
    }

    /** 规范没过时是否直接终止。只有 stop 才直接终止。 */
    public boolean lintFailureStops() {
        return "stop".equalsIgnoreCase(lintOnFailure);
    }

    /** 按开关和失败策略构造本次运行的规范检查。默认先改，次数用尽且测试通过才放行。 */
    public LintGate lintGate() {
        if (!lintGateEnabledValue()) {
            return LintGate.disabled();
        }
        LintGate.FailurePolicy policy = LintGate.FailurePolicy.RETRY;
        if ("stop".equalsIgnoreCase(lintOnFailure)) {
            policy = LintGate.FailurePolicy.STOP;
        } else if ("pass".equalsIgnoreCase(lintOnFailure)) {
            policy = LintGate.FailurePolicy.PASS;
        }
        return new LintGate(true, ProcessLintRunner.INSTANCE, policy);
    }

    /** 通用收尾提醒门默认开启，与实际验证状态和 Goal 独立核验分别处理。 */
    public boolean completionChecksEnabled() {
        return Boolean.TRUE.equals(completionChecks);
    }
}
