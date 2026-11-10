package com.pilot.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.exec.ExecutionBackends;
import com.pilot.server.maintenance.BackupService;
import com.pilot.server.maintenance.RetentionService;
import com.pilot.server.maintenance.StorageHealth;

/**
 * 运维接口：备份、保留策略、存储与隔离状态。
 *
 * <p>这些接口回答的是「数据能不能恢复、隔离有没有生效」，不涉及会话内容本身。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {
    private final BackupService backups;
    private final RetentionService retention;
    private final ExecutionBackends.Selection execution;

    public MaintenanceController(BackupService backups, RetentionService retention,
        ExecutionBackends.Selection execution) {
        this.backups = backups;
        this.retention = retention;
        this.execution = execution;
    }

    @GetMapping("/status")
    public Status status() {
        BackupService.Status status = backups.status();
        return new Status(status, retention.retentionDays(), retention.attachmentsDays(), StorageHealth.snapshot(),
            execution.describe(), execution.isolated(), execution.degraded(), execution.reason());
    }

    @PostMapping("/backup")
    public BackupService.BackupReport backup() {
        return backups.backup();
    }

    @PostMapping("/backup/verify")
    public BackupService.VerificationReport verifyBackup() {
        return backups.verifyLatestBackup();
    }

    @PostMapping("/purge")
    public RetentionService.PurgeReport purge() {
        return retention.purgeNow();
    }

    public record Status(BackupService.Status backup, int retentionDays, int attachmentsDays,
        StorageHealth.Snapshot storage, String executionIsolation, boolean isolated, boolean degraded,
        String degradedReason) {
    }
}
