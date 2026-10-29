package com.pilot.core.tool;

import com.pilot.core.skill.Skill;
import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.skill.SkillManifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 加载技能指引（SKILL.md 正文）或技能目录内的参考文件——渐进式披露的第二、三层入口。
 *
 * <p>技能对模型的常驻可见面只有系统提示词「技能」段的目录（name + description）；
 * 正文按名加载，目录内的 references/、scripts/ 经 {@code file} 参数读取。
 * 描述在 {@code prompts/tools/use_skill.md} 维护（见 {@link #promptDescription()}）。
 *
 * <p>本工具只读（READ_ONLY 全放行）：技能是知识，读知识不需要审批；指引建议的所有动作
 * 仍走既有工具与权限模型——技能是提示词不是代码，不新增任何执行面或权限面。
 */
public final class UseSkillTool implements Tool {

    private final SkillCatalog catalog;

    public UseSkillTool(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "use_skill";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string",
                "description", "系统提示词「技能」一节列出的技能名，必须完全一致"));
        properties.put("file", Map.of("type", "string",
                "description", "可选；技能目录内相对路径（如 references/api.md）。缺省返回 SKILL.md 正文"));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("name"), "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String name = ToolSupport.stringArg(args, "name");
        if (name == null) {
            return "ERROR: 缺少参数 name（技能名见系统提示词「技能」一节）";
        }
        Skill skill = catalog.find(name).orElse(null);
        if (skill == null) {
            return "ERROR: 未知技能 " + name + "；可用技能：" + availableNames();
        }
        String file = ToolSupport.stringArg(args, "file");
        if (file == null) {
            // 正文上限在加载时校验过（500 行），这里不再截断，保证可预期
            return skill.body() + filesFooter(skill);
        }
        try {
            return catalog.readFile(skill, file);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage()
                    + (skill.files().isEmpty() ? "" : "；本技能目录内文件：" + String.join(", ", skill.files()));
        }
    }

    private String availableNames() {
        List<String> names = catalog.list().stream().map(SkillManifest::name).toList();
        return names.isEmpty() ? "（无）" : String.join(", ", names);
    }

    /** Level 3 的发现入口：正文加载完即告诉模型目录里还有什么可读。 */
    private String filesFooter(Skill skill) {
        if (skill.files().isEmpty()) {
            return "";
        }
        return "\n\n本技能目录内文件（用 file 参数读取）：\n"
                + skill.files().stream().map(file -> "- " + file).collect(Collectors.joining("\n"));
    }
}
