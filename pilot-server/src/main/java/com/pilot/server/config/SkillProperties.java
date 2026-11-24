package com.pilot.server.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 技能体系配置。
 *
 * @param enabled 技能总开关：false 时整条链退化——use_skill 不注册、目录段不注入、
 *                {@code GET /api/skills} 返回空（同 {@code multi.enabled} 的条件装配模式）
 * @param userDir 用户级技能目录；不填时依次回落环境变量 {@code PILOT_SKILLS_DIR}、
 *                {@code <pilot.data.dir>/skills}
 * @param builtin 是否加载随发行版携带的内置开箱技能包（默认 true，同名项目/用户技能可覆盖）
 */
@ConfigurationProperties(prefix = "pilot.skills")
public record SkillProperties(Boolean enabled, String userDir, Boolean builtin) {

    @ConstructorBinding
    public SkillProperties {
        enabled = enabled == null || enabled;
        userDir = userDir == null || userDir.isBlank() ? null
            : Path.of(userDir.strip()).toAbsolutePath().normalize().toString();
        builtin = builtin == null || builtin;
    }

    public SkillProperties(Boolean enabled, String userDir) {
        this(enabled, userDir, true);
    }
}
