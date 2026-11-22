package com.pilot.server.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 存储降级状态。
 *
 * <p>快照、清单、证据写入失败时任务仍要继续，但用户必须知道「这些记录已经不可靠」。
 * 组件在 catch 里登记一次，诊断接口与运维接口据此显示降级原因，
 * 而不是只在日志里留一行没人看的 warn。
 */
@Component
public final class StorageHealthService {

    /** @param area 出错的数据区域，例如 {@code multi}、{@code validation} */
    public record Issue(String area, String reason, Instant at) {
    }

    public record Snapshot(boolean healthy, List<Issue> issues, Instant checkedAt) {
        public Snapshot {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private final Map<String, Issue> issues = new ConcurrentHashMap<>();

    public void markDegraded(String area, String reason) {
        if (area == null || area.isBlank()) {
            return;
        }
        issues.put(area, new Issue(area, reason == null ? "未知原因" : reason, Instant.now()));
    }

    public void recovered(String area) {
        if (area != null) {
            issues.remove(area);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(issues.isEmpty(), List.copyOf(issues.values()), Instant.now());
    }
}
