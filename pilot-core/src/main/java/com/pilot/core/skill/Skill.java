package com.pilot.core.skill;

import java.util.List;

/**
 * 一个已解析的可用技能：清单 + 剥离 frontmatter 后的正文 + 目录内其他文件的相对路径清单。
 *
 * <p>正文按需经 {@code use_skill} 注入模型（渐进披露的第二层）；{@code files} 是第三层的发现入口：
 * 正文引用的 references/、scripts/ 由模型用 {@code use_skill} 的 {@code file} 参数按需读取。
 *
 * @param manifest 清单
 * @param body    SKILL.md 去掉 frontmatter 的正文
 * @param files   目录内除 SKILL.md 外的文件清单（{@code /} 分隔的相对路径）
 */
public record Skill(SkillManifest manifest, String body, List<String> files) { }
