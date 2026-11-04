package com.pilot.server.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 技能体系配置。
 *
 * @param enabled 技能总开关：false 时整条链退化——use_skill 不注册、目录段不注入、
 *                {@code GET /api/skills} 返回空（同 {@code multi.enabled} 的条件装配模式）
 * @param userDir 用户级技能目录；不填时依次回落环境变量 {@code PILOT_SKILLS_DIR}、
 *                {@code <pilot.data.dir>/skills}
 */
@ConfigurationProperties(prefix = "pilot.skills")
public record SkillProperties(Boolean enabled, String userDir) {

    public SkillProperties {
        enabled = enabled == null || enabled;
        userDir = userDir == null || userDir.isBlank() ? null
            : Path.of(userDir.strip()).toAbsolutePath().normalize().toString();
    }
}
