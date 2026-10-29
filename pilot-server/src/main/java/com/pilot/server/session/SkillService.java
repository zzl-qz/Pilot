package com.pilot.server.session;

import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.skill.Skill;
import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.skill.SkillSource;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.config.SkillProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话与技能目录的装配点。
 *
 * <p>技能是提示词不是代码：激活的全部效果是注入指引文本，动作仍走既有工具与权限模型。
 * 两级目录——项目级 {@code <workspace>/.pilot/skills}（随仓库走、可提交进项目 git）、
 * 用户级 {@code <pilot.data.dir>/skills}（本机个人库）——同名时项目级遮蔽用户级；
 * 每次 run 重新扫描，改技能文件随下次运行生效（与 {@code PromptLibrary} 外部目录同款热更新）。
 */
@Component
public class SkillService {
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    private final SkillProperties properties;
    private final AgentProperties agentProperties;
    private final PilotDataDir dataDir;

    public SkillService(SkillProperties properties, AgentProperties agentProperties, PilotDataDir dataDir) {
        this.properties = properties;
        this.agentProperties = agentProperties;
        this.dataDir = dataDir;
    }

    /** 项目级技能目录固定为 {@code <workspace>/.pilot/skills}：目录即协议，不设配置。 */
    private Path projectRoot(String workspace) {
        return resolve(workspace).resolve(".pilot").resolve("skills");
    }

    private Path resolve(String workspace) {
        String target = workspace == null || workspace.isBlank()
                ? agentProperties.workspace()
                : workspace;
        return Path.of(target).toAbsolutePath().normalize();
    }

    /** 按工作区扫描两级技能目录；关闭技能时返回 {@link SkillCatalog#EMPTY}。 */
    public SkillCatalog catalogFor(String workspace) {
        if (!properties.enabled()) {
            return SkillCatalog.EMPTY;
        }
        List<SkillCatalog.Root> roots = new ArrayList<>();
        roots.add(new SkillCatalog.Root(projectRoot(workspace), SkillSource.PROJECT));
        roots.add(new SkillCatalog.Root(userRoot(), SkillSource.USER));
        return SkillCatalog.scan(roots);
    }

    private Path userRoot() {
        if (properties.userDir() != null) {
            return Path.of(properties.userDir());
        }
        String fromEnv = System.getenv("PILOT_SKILLS_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv.strip()).toAbsolutePath().normalize();
        }
        return dataDir.file("skills");
    }

    /**
     * 目录段数据（{@code system/skills.md} 的 {@code {{skills}}} 变量）：
     * 每技能一行，只放模型跨技能决策需要的信息；目录是 system 里唯一的技能出处（单一出处原则）。
     */
    public String catalogText(SkillCatalog catalog) {
        return catalog.list().stream()
                .map(manifest -> "- " + manifest.name() + "：" + manifest.description()
                        + (manifest.command() == null ? "" : "（快捷命令 " + manifest.command() + "）"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 显式激活文本：用户点名技能时作为首条 user 消息注入。
     *
     * @return 渲染结果；技能不存在或无效时返回 null，调用方注入说明而不是静默忽略
     */
    public String activateText(SkillCatalog catalog, String name) {
        Skill skill = catalog.find(name).orElse(null);
        if (skill == null) {
            return null;
        }
        return PROMPTS.render("skills/activate", Map.of("name", name, "body", skill.body())).strip();
    }
}
