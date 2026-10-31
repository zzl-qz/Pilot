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
import java.util.stream.Stream;

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
    private final int backupDays;
    private final int validationDays;
    private final ObjectProvider<SqliteStore> store;
    private final RunSnapshotService snapshots;
    private final BackupService backups;
    private final PilotDataDir dataDir;

    public RetentionService(@Value("${pilot.retention.days:0}") int retentionDays,
                            @Value("${pilot.retention.backups-days:0}") int backupDays,
                            @Value("${pilot.retention.validation-days:0}") int validationDays,
                            ObjectProvider<SqliteStore> store,
                            RunSnapshotService snapshots,
                            BackupService backups,
                            PilotDataDir dataDir) {
        this.retentionDays = retentionDays;
        this.backupDays = backupDays;
        this.validationDays = validationDays;
        this.store = store;
        this.snapshots = snapshots;
        this.backups = backups;
        this.dataDir = dataDir;
    }

    @Scheduled(cron = "${pilot.retention.cron:0 30 3 * * *}")
    public void scheduledPurge() {
        if (!enabled()) {
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
        // 三类数据可以有不同的保留期：事件/快照要留够复盘时间，备份和校验证据通常更短
        Instant now = Instant.now();
        int events = 0;
        int snapshotRuns = 0;
        int traces = 0;
        SqliteStore sqlite = store.getIfAvailable();
        if (retentionDays > 0) {
            Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
            events = sqlite == null ? 0 : sqlite.deleteEventsBefore(cutoff);
            snapshotRuns = snapshots.purgeOlderThan(cutoff);
            traces = deleteFilesBefore(dataDir.file("traces"), cutoff);
            if (sqlite != null && events > 0) {
                sqlite.checkpoint();
            }
        }
        int backupCount = backupDays > 0
                ? backups.purgeOlderThan(now.minus(backupDays, ChronoUnit.DAYS)) : 0;
        int validationFiles = validationDays > 0
                ? deleteFilesBefore(dataDir.file("validation"), now.minus(validationDays, ChronoUnit.DAYS)) : 0;
        return new PurgeReport(events, snapshotRuns, traces, retentionDays, backupCount, validationFiles,
                backupDays, validationDays);
    }

    /** 删除某个数据目录下早于 cutoff 的普通文件；目录不存在时返回 0。 */
    private int deleteFilesBefore(Path directory, Instant cutoff) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("清理 {} 失败", file, e);
                    StorageHealth.markDegraded("retention", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("遍历数据目录失败: {}", directory, e);
            StorageHealth.markDegraded("retention", e.getMessage());
        }
        return removed;
    }

    /** 生效的保留天数；{@code 0} 表示关闭。 */
    public int retentionDays() {
        return retentionDays;
    }

    /** 任意一类保留策略是否开启。 */
    public boolean enabled() {
        return retentionDays > 0 || backupDays > 0 || validationDays > 0;
    }

    /**
     * @param events         删除的事件条数
     * @param snapshotRuns   删除的快照运行数
     * @param traces         删除的 trace 文件数
     * @param retentionDays  事件与快照的保留天数
     * @param backups        删除的备份目录数
     * @param validationFiles 删除的校验证据文件数
     * @param backupDays     备份保留天数
     * @param validationDays 校验证据保留天数
     */
    public record PurgeReport(int events, int snapshotRuns, int traces, int retentionDays, int backups,
                              int validationFiles, int backupDays, int validationDays) {
    }
}
