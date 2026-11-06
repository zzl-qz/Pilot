package com.pilot.server.capability;

import java.util.Map;

import com.pilot.core.tool.Tool;

/** 将资源发现接到模型工具表，避免仅在 UI 中展示。 */
public final class LocalResourcesTool implements Tool {
    @Override
    public String name() {
        return "local_resources";
    }

    @Override
    public String description() {
        return "列出本机 PATH 资源及未发现时的替代方案；发现程序不等于已验证运行。内置 office 不依赖本机 Office，额外 Python 包可通过 prepare_runtime 计划和授权准备。";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(new LocalCapabilityService().discover());
    }
}
