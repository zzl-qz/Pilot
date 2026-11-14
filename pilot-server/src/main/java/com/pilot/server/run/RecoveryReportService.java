package com.pilot.server.run;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 最近一次启动恢复的结果。
 *
 * <p>恢复不是「启动时悄悄跑一下」：用户需要看到哪些运行被接管、停在第几步、
 * 下一步本来要做什么、租约是怎么处理的，才能判断能不能直接继续。
 */
@Component
public class RecoveryReportService {
    private volatile Snapshot snapshot = new Snapshot(Instant.EPOCH, "", List.of(), "尚未执行启动恢复", false);

    /**
     * @param completed      本次启动是否已经跑过恢复
     * @param degradedReason 恢复过程中不致命的问题（例如检查点写不进去）
     */
    public record Snapshot(Instant recoveredAt, String instanceId, List<RunStateTracker.RecoveryEntry> entries,
        String note, boolean completed) {
        public Snapshot {
            entries = entries == null ? List.of() : List.copyOf(entries);
            note = note == null ? "" : note;
        }

        public int count() {
            return entries.size();
        }
    }

    public void record(String instanceId, List<RunStateTracker.RecoveryEntry> entries, String note) {
        snapshot = new Snapshot(Instant.now(), instanceId == null ? "" : instanceId, entries, note, true);
    }

    public Snapshot latest() {
        return snapshot;
    }
}
