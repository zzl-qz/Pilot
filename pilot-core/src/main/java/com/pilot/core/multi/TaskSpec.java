package com.pilot.core.multi;

import java.util.List;
import java.util.Objects;

/**
 * 一个子任务的完整定义：目标、验收依据、依赖、权限视图、风险等级。
 *
 * @param id        任务编号，父会话内唯一，例如 {@code T1}
 * @param title     任务标题，界面展示用
 * @param goal      下发给子 Agent 的完整目标（做什么、做到什么算完成）
 * @param dependsOn 前置任务编号；为空表示可以立即并行启动
 * @param scope     文件与工具白名单
 * @param risk      风险等级，决定是否需要强制人工审批
 * @param reviewers 同一任务并行出方案的子 Agent 数量；&gt;1 时结论不一致会触发分歧仲裁
 */
public record TaskSpec(String id, String title, String goal, List<String> dependsOn,
                       AgentScope scope, RiskLevel risk, int reviewers) {
    public TaskSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("任务编号不能为空");
        }
        id = id.strip();
        title = title == null || title.isBlank() ? id : title.strip();
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("任务 " + id + " 缺少目标描述");
        }
        goal = goal.strip();
        dependsOn = dependsOn == null ? List.of() : dependsOn.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(dep -> !dep.isEmpty())
                .distinct()
                .toList();
        if (dependsOn.contains(id)) {
            throw new IllegalArgumentException("任务 " + id + " 不能依赖自己");
        }
        scope = scope == null ? AgentScope.readOnly(List.of(), List.of()) : scope;
        risk = risk == null ? RiskLevel.MEDIUM : risk;
        reviewers = Math.max(1, Math.min(reviewers, 3));
    }

    /** 便捷构造：只读调研任务，文件范围用通配符描述。 */
    public static TaskSpec readOnly(String id, String title, String goal, List<String> dependsOn,
                                    List<String> files) {
        return new TaskSpec(id, title, goal, dependsOn,
                AgentScope.readOnly(files, List.of()), RiskLevel.LOW, 1);
    }

    /** 便捷构造：可提交沙箱改动的任务。 */
    public static TaskSpec editable(String id, String title, String goal, List<String> dependsOn,
                                    List<String> files, RiskLevel risk) {
        return new TaskSpec(id, title, goal, dependsOn,
                AgentScope.readWrite(files, List.of()), risk, 1);
    }

    public TaskSpec withReviewers(int count) {
        return new TaskSpec(id, title, goal, dependsOn, scope, risk, count);
    }
}
