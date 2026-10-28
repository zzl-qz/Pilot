package com.pilot.core.prompt;

import java.util.Set;

/**
 * 系统提示词的组装段落：{@code name} 对应 {@code prompts/<name>.md}，{@code order} 稀疏分配
 * 决定拼接顺序，{@code requiresTools} 声明依赖的工具。
 *
 * <p>顺序语义对齐 dsh 的段落注册表：越靠后的段允许越频繁地变化（环境、约定、计划）。
 * 前缀缓存按 token 匹配，尾部变化只损失尾部缓存。新增段落取现有区间的空隙值
 * （例如又一个工具段取 600–899，别挤占输出与环境段）。
 *
 * <p>{@code requiresTools} 与段落 md 头部 frontmatter 声明二选一：代码声明优先，
 * 没有代码声明时回落到 frontmatter——md 里的标记方便阅读与外部覆盖，真源在注册表。
 *
 * @param name         模板名（不含 .md），如 {@code system/base}
 * @param order        拼接顺序，稀疏分配
 * @param requiresTools 该段依赖的工具；为空表示无条件注入
 */
public record SystemSection(String name, int order, Set<String> requiresTools) {

    public SystemSection {
        requiresTools = requiresTools == null ? Set.copyOf(Set.of()) : Set.copyOf(requiresTools);
    }

    public SystemSection(String name, int order) {
        this(name, order, Set.of());
    }
}
