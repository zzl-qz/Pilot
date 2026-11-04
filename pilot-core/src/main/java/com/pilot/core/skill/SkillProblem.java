package com.pilot.core.skill;

/**
 * 无效技能条目：名称、路径与无法通过校验的原因。
 *
 * <p>对模型 fail-closed（不进目录、不可 {@code use_skill}，破损的指引永远不进模型视野）；
 * 对用户 fail-visible（经 {@code GET /api/skills} 的 {@code invalid} 列表与启动日志亮牌）。
 * 一个坏技能只影响自己，绝不中断会话。
 */
public record SkillProblem(String name, String path, String reason) {
}
