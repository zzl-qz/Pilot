package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.data.DataMaintenance;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.common.data.StorageHealth;
import com.pilot.server.session.AttachmentReferences;
import com.pilot.server.session.AttachmentService;
import com.pilot.server.session.RunRegistry;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.workspace.RunSnapshotService;

/**
 * 运行期数据的保留策略：事件、快照和 trace 都是只增不减的，跑久了会持续占盘。
 *
 * <p>默认关闭（{@code pilot.retention.days=0}），由用户显式开启；开启后每天清理一次
 * 早于保留期的数据。会话本身不删，只是不再保留那么久的事件审计。
 */
@Component
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    /** 抢不到维护锁说明正在备份或恢复，稍等一下就放弃这一轮清理。 */
    private static final Duration MAINTENANCE_LOCK_WAIT = Duration.ofSeconds(2);

    private final int retentionDays;
    private final int backupDays;
    private final int validationDays;
    private final int attachmentsDays;
    private final ObjectProvider<SqliteStore> store;
    private final RunSnapshotService snapshots;
    private final BackupService backups;
    private final PilotDataDir dataDir;
    private final SessionService sessions;
    private final AttachmentService attachments;
    private final ValidationLedger validations;
    private final RunRegistry registry;
    private final AttachmentReferences attachmentReferences;

    public RetentionService(@Value("${pilot.retention.days:0}") int retentionDays,
        @Value("${pilot.retention.backups-days:0}") int backupDays,
        @Value("${pilot.retention.validation-days:0}") int validationDays,
        @Value("${pilot.retention.attachments-days:0}") int attachmentsDays, ObjectProvider<SqliteStore> store,
        RunSnapshotService snapshots, BackupService backups, PilotDataDir dataDir, SessionService sessions,
        AttachmentService attachments, ValidationLedger validations, RunRegistry registry,
        AttachmentReferences attachmentReferences) {
        this.retentionDays = retentionDays;
        this.backupDays = backupDays;
        this.validationDays = validationDays;
        this.attachmentsDays = attachmentsDays;
        this.store = store;
        this.snapshots = snapshots;
        this.backups = backups;
        this.dataDir = dataDir;
        this.sessions = sessions;
        this.attachments = attachments;
        this.validations = validations;
        this.registry = registry;
        this.attachmentReferences = attachmentReferences;
    }

    @Scheduled(cron = "${pilot.retention.cron:0 30 3 * * *}")
    public void scheduledPurge() {
        if (!enabled()) {
            return;
        }
        try {
            log.info("保留策略已执行：{}", purgeNow());
        } catch (DataMaintenance.Busy e) {
            log.info("暂缓清理：{}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("保留策略执行失败", e);
        }
    }

    /** 立即按配置的保留期清理一次；未开启时什么都不做。 */
    public PurgeReport purgeNow() {
        try (var maintenance = dataDir.maintenance().exclusive(MAINTENANCE_LOCK_WAIT)) {
            return purge();
        }
    }

    private PurgeReport purge() {
        // 三类数据可以有不同的保留期：事件/快照要留够复盘时间，备份和校验证据通常更短
        Instant now = Instant.now();
        int events = 0;
        int snapshotRuns = 0;
        int traces = 0;
        SqliteStore sqlite = store.getIfAvailable();
        var running = registry.activeRuns();
        Set<String> activeSessions = new HashSet<>(running.values());
        if (sqlite != null && (retentionDays > 0 || validationDays > 0)) {
            activeSessions.addAll(sqlite.activeSessionIds());
        }
        if (retentionDays > 0) {
            Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
            Set<String> protectedRuns = new HashSet<>(running.keySet());
            Set<String> protectedTraces = new HashSet<>(activeSessions);
            if (sqlite != null) {
                protectedRuns.addAll(sqlite.activeRunIds());
                events = sqlite.deleteEventsBefore(cutoff, Set.copyOf(running.values()));
                var retained = sqlite.retainedEventReferences();
                protectedRuns.addAll(retained.runs());
                protectedTraces.addAll(retained.sessions());
                if (events > 0) {
                    sqlite.checkpoint();
                }
            } else {
                for (Session session : sessions.list()) {
                    var history = sessions.events(session.id());
                    history.forEach(event -> protectedRuns.add(event.runId()));
                    if (!history.isEmpty()) {
                        protectedTraces.add(session.id());
                    }
                }
            }
            snapshotRuns = snapshots.purgeOlderThan(cutoff, protectedRuns);
            traces = deleteTracesBefore(dataDir.file("traces"), cutoff, protectedTraces);
        }
        int backupCount = backupDays > 0 ? backups.purgeOlderThan(now.minus(backupDays, ChronoUnit.DAYS)) : 0;
        int validationFiles = validationDays > 0
            ? validations.purgeOlderThan(now.minus(validationDays, ChronoUnit.DAYS), activeSessions) : 0;
        int attachmentFiles = 0;
        int attachmentDirs = 0;
        if (attachmentsDays > 0) {
            Set<String> retained = new HashSet<>(sessions.list().stream().map(Session::id).toList());
            retained.addAll(attachmentReferences.retainedOwners());
            AttachmentService.PurgeStats stats =
                attachments.purge(now.minus(attachmentsDays, ChronoUnit.DAYS), retained);
            attachmentFiles = stats.files();
            attachmentDirs = stats.dirs();
        }
        return new PurgeReport(events, snapshotRuns, traces, retentionDays, backupCount, validationFiles, backupDays,
            validationDays, attachmentFiles, attachmentDirs);
    }

    /** 删除某个数据目录下早于 cutoff 的普通文件；目录不存在时返回 0。 */
    private int deleteTracesBefore(Path directory, Instant cutoff, Set<String> protectedSessions) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path file : entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                try {
                    String name = file.getFileName().toString();
                    boolean protectedFile = protectedSessions.stream()
                        .anyMatch(session -> name.equals(session + ".jsonl") || name.startsWith(session + "-"));
                    if (!protectedFile && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        if (Files.deleteIfExists(file)) {
                            removed++;
                        }
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
        return retentionDays > 0 || backupDays > 0 || validationDays > 0 || attachmentsDays > 0;
    }

    /** 附件保留天数；{@code 0} 表示关闭。 */
    public int attachmentsDays() {
        return attachmentsDays;
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
     * @param attachments    删除的附件文件数
     * @param attachmentDirs 删除的附件目录数
     */
    public record PurgeReport(int events, int snapshotRuns, int traces, int retentionDays, int backups,
        int validationFiles, int backupDays, int validationDays, int attachments, int attachmentDirs) {
    }
}
