package com.pilot.core.tool;

import java.nio.file.Path;

/**
 * 写文件前的快照回调。工具在真正落盘前调用，让上层有机会备份原内容，
 * 从而支持「回滚本次运行」。实现抛异常时写入会被中止（失败关闭）。
 */
@FunctionalInterface
public interface SnapshotSink {
    void beforeWrite(Path file);

    /** 写入后立即记录工具产出的指纹，以识别运行尚未结束时发生的外部修改。 */
    default void afterWrite(Path file) {
    }

    /**
     * 命令行工具在进程退出后才能知道它实际改了哪些文件。调用方必须传入命令启动前捕获的
     * 原始状态，不能在文件已经变化后再调用 {@link #beforeWrite(Path)}，否则回滚会把新内容
     * 错当成备份。默认实现保持兼容；支持运行回滚的上层实现应覆盖此方法。
     */
    default void externalChange(Path file, FileSnapshot original) {
    }

    /** existed=true 且 content=null 表示已检测到变化，但原文件超过了有界内存快照上限。 */
    record FileSnapshot(boolean existed, byte[] content, String hash) {
        public FileSnapshot {
            content = content == null ? null : content.clone();
            hash = hash == null ? "" : hash;
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    static SnapshotSink none() {
        return file -> {
        };
    }
}
