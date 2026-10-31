package com.pilot.core.tool;

import com.pilot.core.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 工具注册表：按名称查找工具，并生成模型请求所需的工具定义。
 *
 * <p>{@link #find} 返回所有已注册工具（包括暂不可用的，执行层据此给出明确原因）；
 * {@link #definitions()} 与 {@link #names()} 只包含当前可用的工具，
 * 即「向模型声明的能力」以运行时实际握手结果为准，而不是以注册为准。
 */
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

    /** 全部已注册工具，含暂不可用的。 */
    public List<Tool> tools() {
        ArrayList<Tool> result = new ArrayList<>(tools.values());
        result.sort(Comparator.comparing(Tool::name));
        return List.copyOf(result);
    }

    /** 当前可用的工具。 */
    public List<Tool> availableTools() {
        return tools().stream().filter(tool -> available(tool).available()).toList();
    }

    /** 已注册但当前不可用的工具及原因，供系统提示词和界面说明缺口。 */
    public Map<String, String> unavailable() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Tool tool : tools()) {
            Availability availability = available(tool);
            if (!availability.available()) result.put(tool.name(), availability.reason());
        }
        return Map.copyOf(result);
    }

    /** 名称与定义按名称排序，保证相同工具集的请求体稳定（便于缓存与测试）。 */
    public List<ToolDefinition> definitions() {
        return availableTools().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    /** 当前可用的工具名，供系统提示词按工具过滤依赖段落。 */
    public Set<String> names() {
        return availableTools().stream().map(Tool::name).collect(Collectors.toUnmodifiableSet());
    }

    public Map<String, Tool> asMap() {
        return Map.copyOf(tools);
    }

    /** 能力探测抛异常按不可用处理：宁可少声明，也不能让注册表读取失败拖垮请求。 */
    static Availability available(Tool tool) {
        try {
            Availability availability = tool.availability();
            return availability == null ? Availability.ready() : availability;
        } catch (RuntimeException e) {
            return Availability.unavailable("能力探测失败：" + e.getMessage());
        }
    }
}
