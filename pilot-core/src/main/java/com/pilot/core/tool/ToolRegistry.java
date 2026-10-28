package com.pilot.core.tool;

import com.pilot.core.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 工具注册表：按名称查找工具，并生成模型请求所需的工具定义。 */
public final class ToolRegistry {
    private final ConcurrentMap<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        Tool previous = tools.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalArgumentException("tool already registered: " + tool.name());
        }
    }

    public Optional<Tool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    public List<Tool> tools() {
        var result = new ArrayList<>(tools.values());
        result.sort(Comparator.comparing(Tool::name));
        return List.copyOf(result);
    }

    /** 名称与定义按名称排序，保证相同工具集的请求体稳定（便于缓存与测试）。 */
    public List<ToolDefinition> definitions() {
        return tools().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    /** 当前已注册的工具名，供系统提示词按工具过滤依赖段落。 */
    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }

    public Map<String, Tool> asMap() {
        return Map.copyOf(tools);
    }
}
