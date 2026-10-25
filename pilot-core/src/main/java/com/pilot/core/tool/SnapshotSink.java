package com.pilot.core.tool;

import java.nio.file.Path;

/**
 * 写文件前的快照回调。工具在真正落盘前调用，让上层有机会备份原内容，
 * 从而支持「回滚本次运行」。实现抛异常时写入会被中止（失败关闭）。
 */
@FunctionalInterface
public interface SnapshotSink {
    void beforeWrite(Path file);

    static SnapshotSink none() {
        return file -> {
        };
    }
}
