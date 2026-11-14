package com.pilot.server.skill;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;

import tools.jackson.databind.ObjectMapper;

/** 用户指定来源的安装入口；不执行包内脚本，也不改变既有权限策略。 */
public record InstallSkillTool(SkillInstallService installer, SkillService skills, ObjectMapper mapper,
    String workspace) implements Tool {
    @Override
    public String name() {
        return "install_skill";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        return args != null && "inspect".equals(args.get("action"));
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", Map.of("type", "string", "enum", List.of("inspect", "install"), "description",
            "inspect 查看候选；install 下载并安装"));
        fields.put("source", ToolSchemas.string("技能目录、SKILL.md 或 Git 仓库的 HTTP(S) 链接，也支持本机绝对目录"));
        fields.put("token", ToolSchemas.string("可选：上次返回的预览 token，复用下载内容；与 source 二选一"));
        fields.put("names",
            Map.of("type", "array", "items", Map.of("type", "string"), "description", "选择安装的技能名；只有一个候选时可省略"));
        fields.put("scope", Map.of("type", "string", "enum", List.of("project", "user"), "description",
            "默认 project；用户明确要求所有项目共享时用 user"));
        fields.put("ref", ToolSchemas.string("可选：分支、标签或提交；带斜杠的分支须明确指定"));
        fields.put("path", ToolSchemas.string("可选：仓库内技能相对目录"));
        fields.put("overwrite", ToolSchemas.bool("默认 false；只有用户明确要求替换已有技能才用 true"));
        return ToolSchemas.object(fields, List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String action = ToolSupport.stringArg(args, "action");
            if (!List.of("inspect", "install").contains(action == null ? "" : action)) {
                return "ERROR: action 必须是 inspect 或 install";
            }
            String scope = ToolSupport.stringArg(args, "scope");
            if (scope != null && !List.of("project", "user").contains(scope)) {
                return "ERROR: scope 必须是 project 或 user";
            }
            String source = ToolSupport.stringArg(args, "source");
            String token = ToolSupport.stringArg(args, "token");
            if ((source == null) == (token == null)) {
                return "ERROR: 请提供 source 或 token，不能同时提供";
            }
            List<String> names = new ArrayList<>();
            if (args.containsKey("names")) {
                if (!(args.get("names") instanceof List<?> values)) {
                    return "ERROR: names 必须是技能名列表";
                }
                for (Object value : values) {
                    if (!(value instanceof String name) || name.isBlank()) {
                        return "ERROR: names 必须是非空技能名列表";
                    }
                    names.add(name.strip());
                }
            }
            if (args.containsKey("overwrite") && !(args.get("overwrite") instanceof Boolean)) {
                return "ERROR: overwrite 必须是布尔值";
            }
            if (source != null) {
                var preview = installer.preview(source, workspace, ToolSupport.stringArg(args, "ref"),
                    ToolSupport.stringArg(args, "path"));
                token = preview.token();
                if (preview.candidates().stream().noneMatch(SkillInstallService.Candidate::valid)) {
                    return "ERROR: " + mapper.writeValueAsString(
                        Map.of("status", "no_valid_skills", "preview", preview, "message", "未找到可安装的技能，请检查链接和候选的校验原因。"));
                }
                if (action.equals("inspect") || (names.isEmpty()
                    && (preview.candidates().size() != 1 || !preview.candidates().getFirst().valid()))) {
                    return mapper.writeValueAsString(
                        Map.of("status", action.equals("inspect") ? "inspected" : "selection_required", "preview",
                            preview, "message", "根据用户需求选择候选技能，再用 token 和 names 安装；没有明确选择时询问用户，不要安装整个集合。"));
                }
                if (names.isEmpty()) {
                    names.add(preview.candidates().getFirst().name());
                }
            } else if (action.equals("inspect")) {
                return "ERROR: inspect 需要 source；已有 token 可直接选择 names 安装";
            }
            boolean project = !"user".equals(scope);
            var installed = installer.install(token, names.stream().distinct().toList(), project, workspace,
                Boolean.TRUE.equals(args.get("overwrite")));
            var catalog = skills.catalogFor(workspace);
            Path root = (project ? skills.projectRootFor(workspace) : skills.userRootOf()).toAbsolutePath().normalize();
            List<Map<String, Object>> results = new ArrayList<>();
            boolean anyInstalled = false;
            for (var outcome : installed.results()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", outcome.name());
                result.put("status", outcome.status());
                if (outcome.reason() != null) {
                    result.put("reason", outcome.reason());
                }
                if (outcome.status().equals("installed")) {
                    anyInstalled = true;
                    Path directory = root.resolve(outcome.name());
                    boolean available = catalog.find(outcome.name())
                        .map(skill -> skill.manifest().directory().equals(directory)).orElse(false);
                    result.put("directory", directory.toString());
                    result.put("available", available);
                    if (!available) {
                        result.put("reason",
                            catalog.invalid().stream().filter(problem -> problem.name().equals(outcome.name()))
                                .map(com.pilot.core.skill.SkillProblem::reason).findFirst()
                                .orElse("同名项目技能优先；本次安装的用户技能未在当前会话生效"));
                    }
                }
                results.add(result);
            }
            String response = mapper.writeValueAsString(Map.of("token", token, "results", results, "next",
                "对 available=true 的技能立即用 use_skill 加载，检查指引里的依赖、运行环境和配置，按现有权限完成必要设置与验证；只有下载成功不能宣称依赖或执行已验证。缺少凭据须向用户说明，不能编造。"));
            return anyInstalled ? response : "ERROR: " + response;
        } catch (RuntimeException failure) {
            return "ERROR: 安装技能失败：" + failure.getMessage();
        }
    }
}
