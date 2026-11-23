package com.pilot.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把低频工具的参数说明留到真正要用的那一轮再放进请求。
 * 每次请求少带一整份用不到的 schema，长任务才不容易被固定底噪顶满。
 */
public final class EnableToolsTool implements Tool {
    public static final String NAME = "enable_tools";

    private final ToolRegistry registry;

    public EnableToolsTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "打开还没放进本次请求的扩展工具。names 填工具名，例如 browser、office、mcp_call、publish_artifact、remember、read_memory。打开后从下一轮开始才能按完整参数调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("names", Map.of("type", "array", "description", "要打开的工具名", "items", Map.of("type", "string"))),
            List.of("names"));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("names");
        List<String> names = new ArrayList<>();
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    names.add(value.toString().strip());
                }
            }
        } else if (raw != null && !raw.toString().isBlank()) {
            names.add(raw.toString().strip());
        }
        List<String> opened = registry.enable(names);
        if (opened.isEmpty()) {
            return "没有新打开的工具。只填写已经安装、但当前请求里还没有参数说明的扩展工具名。";
        }
        return "已打开：" + String.join("、", opened) + "。下一轮请求会带上它们的参数说明。";
    }
}
