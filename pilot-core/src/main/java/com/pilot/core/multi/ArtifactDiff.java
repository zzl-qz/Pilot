package com.pilot.core.multi;

import java.util.List;

/**
 * 两版产物清单的差异。
 *
 * @param previousCandidate 上一版的清单指纹；没有上一版时为 {@code null}
 */
public record ArtifactDiff(String previousCandidate, String currentCandidate, List<String> added, List<String> removed,
    List<String> modified, List<String> unchanged) {
    public ArtifactDiff {
        added = added == null ? List.of() : List.copyOf(added);
        removed = removed == null ? List.of() : List.copyOf(removed);
        modified = modified == null ? List.of() : List.copyOf(modified);
        unchanged = unchanged == null ? List.of() : List.copyOf(unchanged);
    }

    public boolean changed() {
        return !added.isEmpty() || !removed.isEmpty() || !modified.isEmpty();
    }
}
