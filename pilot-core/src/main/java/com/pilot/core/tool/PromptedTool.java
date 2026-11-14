package com.pilot.core.tool;

import java.util.List;
import java.util.Map;

/** 使用提示词资源描述、默认具有副作用的工具。 */
public abstract class PromptedTool implements Tool {
    private final String name;

    protected PromptedTool(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    protected static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
