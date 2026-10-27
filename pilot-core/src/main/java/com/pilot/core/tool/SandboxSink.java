package com.pilot.core.tool;

/**
 * 子 Agent 的改动出口：写工具不落盘，只把「改前 / 改后」交给沙箱。
 *
 * <p>与 {@link SnapshotSink} 的区别：快照是「真实写入前的备份」，这里是「根本不写真实文件」。
 */
@FunctionalInterface
public interface SandboxSink {
    /**
     * @param path    工作区相对路径（已通过路径校验）
     * @param oldText 改动前内容；{@code null} 表示新建文件
     * @param newText 改动后内容
     */
    void record(String path, String oldText, String newText);

    /** 没有沙箱时的兜底：任何写入都直接报错，避免误落盘。 */
    static SandboxSink rejectAll() {
        return (path, oldText, newText) -> {
            throw new IllegalStateException("当前子 Agent 没有绑定变更沙箱，拒绝写入: " + path);
        };
    }
}
