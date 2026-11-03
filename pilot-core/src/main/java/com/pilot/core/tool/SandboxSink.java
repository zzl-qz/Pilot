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

    default String currentText(String path, String baselineText) {
        return baselineText;
    }

    default boolean proposed(String path) {
        return false;
    }

    default String proposedText(String path) {
        return null;
    }

    /**
     * 把上游产物叠加到本任务的沙箱之上：读取时上游产物优先于磁盘内容，写入仍只进本任务沙箱。
     *
     * <p>没有这一层时，下游子 Agent 读到的还是旧接口、甚至看不到上游新建的文件，
     * 依赖顺序就只是「先跑后跑」，没有真正把产物交出去。
     */
    default SandboxSink overlay(SandboxSink upstream) {
        SandboxSink self = this;
        if (upstream == null) {
            return self;
        }
        return new SandboxSink() {
            @Override
            public void record(String path, String oldText, String newText) {
                self.record(path, oldText, newText);
            }

            @Override
            public String currentText(String path, String baselineText) {
                return self.currentText(path, upstream.currentText(path, baselineText));
            }

            @Override
            public boolean proposed(String path) {
                return self.proposed(path) || upstream.proposed(path);
            }

            @Override
            public String proposedText(String path) {
                String own = self.proposedText(path);
                return own != null ? own : upstream.proposedText(path);
            }
        };
    }

    /** 没有沙箱时的兜底：任何写入都直接报错，避免误落盘。 */
    static SandboxSink rejectAll() {
        return (path, oldText, newText) -> {
            throw new IllegalStateException("当前子 Agent 没有绑定变更沙箱，拒绝写入: " + path);
        };
    }
}
