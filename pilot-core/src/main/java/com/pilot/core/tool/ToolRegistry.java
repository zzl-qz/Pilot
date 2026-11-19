package com.pilot.core.tool;

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

import com.pilot.core.model.ToolDefinition;

/**
 * 工具注册表：按名称查找工具，并生成模型请求所需的工具定义。
 *
 * <p>{@link #find} 返回所有已注册工具（包括暂不可用的，执行层据此给出明确原因）；
 * {@link #definitions()} 与 {@link #names()} 只包含当前可用的工具，
 * 即「向模型声明的能力」以运行时实际握手结果为准，而不是以注册为准。
 */
public final class ToolRegistry {
    /**
     * 低频工具不进每次请求的 schema。模型需要时调用 enable_tools，或直接点名，执行层会当场打开。
     * 名字放在这里，复制到新注册表后仍然生效。
     * install_skill 和 propose_skill 不放进来：目录为空时模型只能靠它们在同一轮里安装并加载。
     */
    private static final Set<String> DEFERRED = Set.of("office", "browser", "prepare_runtime", "local_resources",
        "publish_artifact", "dispatch_agents", "mcp_tools", "mcp_call", "code_diagnostics");
    private final ConcurrentMap<String, Tool> tools = new ConcurrentHashMap<>();
    private final Set<String> enabled = ConcurrentHashMap.newKeySet();

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

    /** Remove a run-specific tool that is superseded by a provider-native capability. */
    public Optional<Tool> remove(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.remove(name));
    }

    /** 全部已注册工具，含暂不可用的。 */
    public List<Tool> tools() {
        ArrayList<Tool> result = new ArrayList<>(tools.values());
        result.sort(Comparator.comparing(Tool::name));
        return List.copyOf(result);
    }

    /** 当前可用、并且已经向模型公开 schema 的工具。 */
    public List<Tool> availableTools() {
        return tools().stream().filter(this::listed).toList();
    }

    /** 把延迟工具加入后续请求。未知名字忽略，避免模型打错一个名字就把整次调用变成失败。 */
    public List<String> enable(List<String> names) {
        ArrayList<String> opened = new ArrayList<>();
        if (names == null) {
            return List.of();
        }
        for (String name : names) {
            if (name == null || name.isBlank() || !tools.containsKey(name.strip())) {
                continue;
            }
            String key = name.strip();
            if (DEFERRED.contains(key)) {
                enabled.add(key);
                opened.add(key);
            }
        }
        return List.copyOf(opened);
    }

    /** 模型直接调用了还没公开的延迟工具时，当场打开，这一次调用照常执行。 */
    public void enableIfDeferred(String name) {
        if (name != null && DEFERRED.contains(name) && tools.containsKey(name)) {
            enabled.add(name);
        }
    }

    private boolean listed(Tool tool) {
        if (!available(tool).available()) {
            return false;
        }
        return !DEFERRED.contains(tool.name()) || enabled.contains(tool.name());
    }

    /** 已注册但当前不可用的工具及原因，供系统提示词和界面说明缺口。 */
    public Map<String, String> unavailable() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Tool tool : tools()) {
            Availability availability = available(tool);
            if (!availability.available()) {
                result.put(tool.name(), availability.reason());
            }
        }
        return Map.copyOf(result);
    }

    /** 名称与定义按名称排序，保证相同工具集的请求体稳定（便于缓存与测试）。 */
    public List<ToolDefinition> definitions() {
        return availableTools().stream()
            .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters())).toList();
    }

    /** 当前可用的工具名，供系统提示词按工具过滤依赖段落。 */
    public Set<String> names() {
        return availableTools().stream().map(Tool::name).collect(Collectors.toUnmodifiableSet());
    }

    public Map<String, Tool> asMap() {
        return Map.copyOf(tools);
    }

    /** 能力探测抛异常按不可用处理：宁可少声明，也不能让注册表读取失败拖垮请求。 */
    public static Availability available(Tool tool) {
        try {
            Availability availability = tool.availability();
            return availability == null ? Availability.ready() : availability;
        } catch (RuntimeException e) {
            return Availability.unavailable("能力探测失败：" + e.getMessage());
        }
    }
}
