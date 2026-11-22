package com.pilot.server.compat;

import java.nio.file.Path;
import java.util.Set;

/**
 * 诊断类功能需要的两个最小能力：把工作区字符串解析成绝对路径、列出已注册工具名。
 *
 * <p>存在的理由是不让「插件兼容判断」反过来依赖 {@code tool} 包：它只需要「有哪些工具」这一件事，
 * 不需要工具工厂本身。用一个窄接口表达需求，依赖方向就回到自上而下。
 */
public interface WorkspaceToolLookup {
    /** 与工具工厂相同的解析口径：空值回退到配置的默认工作区。 */
    Path resolve(String workspace);

    /** 当前工作区已注册的工具名；取不到时返回空集合，调用方按「无法判断」处理。 */
    Set<String> toolNames(String workspace);
}
