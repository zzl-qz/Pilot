package com.pilot.core.session;

public record PreviewLimits(long mergeMillis, int mergeBytes, int snapshotBytes, long totalSnapshotBytes,
    int sessions) {
    public PreviewLimits {
        if (mergeMillis < 1 || mergeBytes < 4 || snapshotBytes < mergeBytes || totalSnapshotBytes < snapshotBytes
            || sessions < 1) {
            throw new IllegalArgumentException("临时输出预算无效");
        }
    }

    public static PreviewLimits defaults() {
        return new PreviewLimits(50, 8192, 128 * 1024, 16L * 1024 * 1024, 256);
    }
}
