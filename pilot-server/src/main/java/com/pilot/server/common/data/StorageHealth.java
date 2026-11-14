package com.pilot.server.common.data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储降级状态。
 *
 * <p>快照、清单、证据写入失败时任务仍要继续，但用户必须知道「这些记录已经不可靠」。
 * 组件在 catch 里登记一次，诊断接口与运维接口据此显示降级原因，
 * 而不是只在日志里留一行没人看的 warn。
 */
public final class StorageHealth {

    /** @param area 出错的数据区域，例如 {@code multi}、{@code validation} */
    public record Issue(String area, String reason, Instant at) {
    }

    public record Snapshot(boolean healthy, List<Issue> issues, Instant checkedAt) {
        public Snapshot {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private static final Map<String, Issue> ISSUES = new ConcurrentHashMap<>();

    private StorageHealth() {
    }

    public static void markDegraded(String area, String reason) {
        if (area == null || area.isBlank()) {
            return;
        }
        ISSUES.put(area, new Issue(area, reason == null ? "未知原因" : reason, Instant.now()));
    }

    public static void recovered(String area) {
        if (area != null) {
            ISSUES.remove(area);
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(ISSUES.isEmpty(), List.copyOf(ISSUES.values()), Instant.now());
    }

    /** 测试用：清空全局状态。 */
    public static void reset() {
        ISSUES.clear();
    }
}
