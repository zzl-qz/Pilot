package com.pilot.core.coordination;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 文件或目录占用：占用方在租约期内独占某个位置。
 *
 * <p>占用只影响协作判断——真正写盘仍由拥有工作区的会话自己执行；这里保存的是规范化的绝对路径，
 * 父子目录之间的重叠也算冲突。
 */
public record CoordinationClaim(String id, String projectId, String sessionId, String path, CoordinationClaimKind kind,
    Instant claimedAt, Instant expiresAt) {

    private static final String SEPARATOR = "/";

    public CoordinationClaim {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean ownedBy(String candidateSessionId) {
        return sessionId.equals(candidateSessionId);
    }

    /**
     * 两个占用是否重叠：目录占用覆盖其下全部文件，父子目录也互为冲突。
     *
     * @param caseInsensitive 与协调范围一致的路径大小写语义
     */
    public boolean overlaps(CoordinationClaim other, boolean caseInsensitive) {
        if (other == null) {
            return false;
        }
        String mine = key(path, caseInsensitive);
        String theirs = key(other.path(), caseInsensitive);
        if (mine.isEmpty() || theirs.isEmpty()) {
            return mine.equals(theirs);
        }
        return covers(mine, theirs) || covers(theirs, mine);
    }

    /** 占用是否覆盖这个路径；用于「发生冲突时阻止危险操作」。 */
    public boolean covers(String candidatePath, boolean caseInsensitive) {
        String mine = key(path, caseInsensitive);
        String target = key(candidatePath, caseInsensitive);
        return mine.isEmpty() ? target.isEmpty() : covers(mine, target);
    }

    private static boolean covers(String parentKey, String childKey) {
        return childKey.equals(parentKey) || childKey.startsWith(parentKey + SEPARATOR);
    }

    private static String key(String path, boolean caseInsensitive) {
        try {
            return ProjectCoordinationScope.directoryKey(ProjectCoordinationScope.canonical(Path.of(path)),
                caseInsensitive);
        } catch (InvalidPathException invalid) {
            return caseInsensitive ? path.toLowerCase(Locale.ROOT) : path;
        }
    }
}
