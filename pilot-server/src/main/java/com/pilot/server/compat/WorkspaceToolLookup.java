package com.pilot.server.compat;

import java.nio.file.Path;
import java.util.Set;

/** 兼容性检查需要的两个最小能力：解析工作区路径并列出已注册工具名。 */
public interface WorkspaceToolLookup {
    /** 与工具工厂相同的解析口径：空值回退到配置的默认工作区。 */
    Path resolve(String workspace);

    /** 当前工作区已注册的工具名；取不到时返回空集合，调用方按「无法判断」处理。 */
    Set<String> toolNames(String workspace);
}
