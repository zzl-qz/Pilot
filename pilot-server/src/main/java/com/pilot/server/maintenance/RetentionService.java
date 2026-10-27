package com.pilot.server.maintenance;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.workspace.RunSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 运行期数据的保留策略：事件、快照和 trace 都是只增不减的，跑久了会持续占盘。
 *
 * <p>默认关闭（{@code pilot.retention.days=0}），由用户显式开启；开启后每天清理一次
 * 早于保留期的数据。会话本身不删，只是不再保留那么久的事件审计。
 */
@Component
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final int retentionDays;
    private final ObjectProvider<SqliteStore> store;
    private final RunSnapshotService snapshots;
    private final PilotDataDir dataDir;

    public RetentionService(@Value("${pilot.retention.days:0}") int retentionDays,
                            ObjectProvider<SqliteStore> store,
                            RunSnapshotService snapshots,
                            PilotDataDir dataDir) {
        this.retentionDays = retentionDays;
        this.store = store;
        this.snapshots = snapshots;
        this.dataDir = dataDir;
    }

    @Scheduled(cron = "${pilot.retention.cron:0 30 3 * * *}")
    public void scheduledPurge() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            log.info("保留策略已执行：{}", purgeNow());
        } catch (RuntimeException e) {
            log.warn("保留策略执行失败", e);
        }
    }

    /** 立即按配置的保留期清理一次；未开启时什么都不做。 */
    public PurgeReport purgeNow() {
        if (retentionDays <= 0) {
            return new PurgeReport(0, 0, 0, 0);
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        SqliteStore sqlite = store.getIfAvailable();
        int events = sqlite == null ? 0 : sqlite.deleteEventsBefore(cutoff);
        int snapshotRuns = snapshots.purgeOlderThan(cutoff);
        int traces = deleteTracesBefore(cutoff);
        if (sqlite != null && events > 0) {
            sqlite.checkpoint();
        }
        return new PurgeReport(events, snapshotRuns, traces, retentionDays);
    }

    private int deleteTracesBefore(Instant cutoff) {
        Path traces = dataDir.file("traces");
        if (!Files.isDirectory(traces)) {
            return 0;
        }
        int removed = 0;
        try (var entries = Files.list(traces)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("清理 trace {} 失败", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("遍历 trace 目录失败: {}", traces, e);
        }
        return removed;
    }

    /** 生效的保留天数；{@code 0} 表示关闭。 */
    public int retentionDays() {
        return retentionDays;
    }

    /**
     * @param events        删除的事件条数
     * @param snapshotRuns  删除的快照运行数
     * @param traces        删除的 trace 文件数
     * @param retentionDays 生效的保留天数
     */
    public record PurgeReport(int events, int snapshotRuns, int traces, int retentionDays) {
    }
}
