package com.pilot.server.workspace;

import java.nio.file.Path;

/** 历史回退的完整文件前后像；null 表示不存在，保留原始字节。 */
public record HistoryFileChange(Path root, Path target, byte[] before, byte[] after) {
    public HistoryFileChange {
        root = root.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        before = before == null ? null : before.clone();
        after = after == null ? null : after.clone();
    }
}
