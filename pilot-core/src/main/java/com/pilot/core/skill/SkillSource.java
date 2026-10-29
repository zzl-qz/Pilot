package com.pilot.core.skill;

/**
 * 技能的来源层级，决定同名冲突时的遮蔽方向。
 *
 * <ul>
 *   <li>{@link #PROJECT}：{@code <workspace>/.pilot/skills/}，随仓库走，可提交进项目 git 与团队共享；</li>
 *   <li>{@link #USER}：{@code <pilot.data.dir>/skills/}，本机个人技能库，对全部工作区生效；</li>
 *   <li>{@code BUILTIN}：预留，随发行版经 classpath 携带的内置技能包（v1 未实现）。</li>
 * </ul>
 */
public enum SkillSource {
    PROJECT,
    USER
}
