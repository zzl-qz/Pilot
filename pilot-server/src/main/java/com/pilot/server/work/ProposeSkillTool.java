package com.pilot.server.work;

import java.util.List;
import java.util.Map;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSupport;

import tools.jackson.databind.ObjectMapper;

/** 提案本身不安装、不执行技能，审核入口只提供给用户。 */
public record ProposeSkillTool(SkillDraftService drafts, ObjectMapper mapper, String sessionId,
    String runId) implements Tool {
    @Override
    public String name() {
        return "propose_skill";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("name", Map.of("type", "string"), "description", Map.of("type", "string"), "body",
                Map.of("type", "string"), "evidence", Map.of("type", "string")),
            "required", List.of("name", "description", "body", "evidence"), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            var draft = drafts.propose(sessionId, runId, ToolSupport.stringArg(args, "name"),
                ToolSupport.stringArg(args, "description"), ToolSupport.stringArg(args, "body"),
                ToolSupport.stringArg(args, "evidence"));
            return mapper.writeValueAsString(
                Map.of("id", draft.id(), "status", draft.status(), "message", "草稿已保存；只有用户在成果面板接受后才生效，不要自行安装或审批。"));
        } catch (RuntimeException e) {
            return "ERROR: 保存技能草稿失败：" + e.getMessage();
        }
    }
}
