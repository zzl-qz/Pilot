package com.pilot.server.work;

import java.util.List;
import java.util.Map;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSupport;

import tools.jackson.databind.ObjectMapper;

/** 将工作区内已生成的交付文件保存为当前运行的独立快照。 */
public record PublishArtifactTool(WorkProductService products, ObjectMapper mapper, String sessionId,
    String runId) implements Tool {
    @Override
    public String name() {
        return "publish_artifact";
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
            Map.of("path", Map.of("type", "string", "description", "工作区内已生成的交付文件路径"), "title", Map.of("type", "string"),
                "summary", Map.of("type", "string"), "sources",
                Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("path", "title"), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            Object value = args.get("sources");
            if (value != null
                && (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))) {
                throw new IllegalArgumentException("sources 必须是网址字符串数组");
            }
            List<String> sources =
                value instanceof List<?> list ? list.stream().map(String.class::cast).toList() : List.of();
            var product = products.publish(sessionId, runId, ToolSupport.stringArg(args, "path"),
                ToolSupport.stringArg(args, "title"), ToolSupport.stringArg(args, "summary"), sources);
            return mapper.writeValueAsString(Map.of("id", product.id(), "title", product.title(), "status", "saved",
                "message", "已保存成果快照，可在成果面板预览和下载；不代表内容已验证。"));
        } catch (RuntimeException e) {
            return "ERROR: 保存成果失败：" + e.getMessage();
        }
    }
}
