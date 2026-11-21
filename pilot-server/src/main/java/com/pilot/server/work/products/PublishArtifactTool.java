package com.pilot.server.work.products;

import java.util.List;
import java.util.Map;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
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
    public boolean readOnly() {
        return false;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("path", ToolSchemas.string("工作区内已生成的交付文件路径"), "title", Map.of("type", "string"), "summary",
                Map.of("type", "string"), "sources", Map.of("type", "array", "items", Map.of("type", "string"))),
            List.of("path", "title"));
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
                "verification", verdict(product)));
        } catch (RuntimeException e) {
            return "ERROR: 保存成果失败：" + e.getMessage();
        }
    }

    /**
     * 如实回报验证结论，不写免责句。
     *
     * <p>模型需要看到「这份成果有没有证据」才能决定是继续补验证还是如实收尾；
     * 一律写「不代表内容已验证」等于什么都没告诉它，也就等于没做这件事。
     */
    private static Map<String, Object> verdict(WorkProductService.Product product) {
        var verification = product.verification();
        return Map.of("status", verification.status(), "reason", verification.reason(), "candidateHash",
            verification.candidateHash(), "message", message(verification));
    }

    private static String message(com.pilot.server.validation.ValidationSnapshot verification) {
        return switch (verification.status()) {
            case com.pilot.server.validation.ValidationSnapshot.VERIFIED -> "已验证：这条证据仍覆盖当前文件内容。";
            case com.pilot.server.validation.ValidationSnapshot.STALE ->
                "证据已过期：" + verification.reason() + "。重新运行校验后再登记。";
            default -> "未验证：" + verification.reason() + "。";
        };
    }
}
