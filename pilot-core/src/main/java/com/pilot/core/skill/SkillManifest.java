package com.pilot.core.skill;

import java.nio.file.Path;
import java.util.List;

/**
 * SKILL.md frontmatter 的解析结果。
 *
 * <p>目录名是技能的真源名：{@code name} 键可选，填写时必须与目录名一致；
 * {@code description} 必填（≤500 字符），它是系统提示词「技能」目录段里模型唯一的判断依据，
 * 要写「何时用」而不只是「是什么」。{@code command} 可选，声明后技能可暴露为输入框快捷指令；
 * {@code allowed-tools} v1 仅作展示（供用户评估该技能的破坏面），没有运行时强制语义。
 *
 * @param name         技能名，与目录名一致
 * @param description  触发条件描述，常驻 system 的目录段
 * @param command      快捷指令名（如 {@code /review}），没有则为 null
 * @param argumentHint 前端展示的参数提示，没有则为 null
 * @param allowedTools 该技能预计使用的工具名清单，仅展示
 * @param directory    技能目录的绝对路径
 * @param source       来源层级
 */
public record SkillManifest(String name, String description, String command,
                            String argumentHint, List<String> allowedTools,
                            Path directory, SkillSource source) { }
