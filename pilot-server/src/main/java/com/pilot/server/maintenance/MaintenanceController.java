package com.pilot.server.maintenance;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.contract.Contracts;
import com.pilot.core.contract.Contracts.Contract;
import com.pilot.core.exec.ExecutionBackends;
import com.pilot.server.common.data.DataMaintenance;
import com.pilot.server.common.data.StorageHealth;
import com.pilot.server.compat.PluginCompatService;
import com.pilot.server.maintenance.backup.BackupService;

import lombok.RequiredArgsConstructor;

/**
 * 运维接口：备份、保留策略、存储与隔离状态。
 *
 * <p>这些接口回答的是「数据能不能恢复、隔离有没有生效」，不涉及会话内容本身。
 */
@RestController
@RequestMapping("/api/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {
    private final BackupService backups;
    private final RetentionService retention;
    private final ExecutionBackends.Selection execution;
    private final PluginCompatService plugins;

    @GetMapping("/status")
    public Status status() {
        BackupService.Status status = backups.status();
        return new Status(status, retention.retentionDays(), retention.attachmentsDays(), StorageHealth.snapshot(),
            execution.describe(), execution.isolated(), execution.degraded(), execution.reason());
    }

    /**
     * 升级前体检：列出这次变化会打掉、降级或保持不动的插件，并附一份可粘贴的 Markdown 报告。
     *
     * <p>只读：不启动 MCP 服务器、不执行钩子、不改配置。
     */
    @GetMapping("/plugin-compat")
    public PluginCompatService.Report pluginCompat(@RequestParam(name = "session", required = false) String session) {
        return plugins.inspect(session);
    }

    /**
     * 核心当前提供的契约表。
     *
     * <p>插件写的是契约版本而不是核心版本号，所以这张表才是它们真正的依赖清单；
     * 对外暴露出来，插件作者不必读源码就能确认自己声明的东西还在不在。
     */
    @GetMapping("/contracts")
    public ContractCatalogue contracts() {
        return new ContractCatalogue(Contracts.publishedContracts(), Contracts.catalogue());
    }

    /**
     * @param contracts 已发布契约，含 id、major、引入版本与说明
     * @param catalogue {@code id@major → 说明} 的扁平对照
     */
    public record ContractCatalogue(List<Contract> contracts, Map<String, String> catalogue) {
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
        try {
            return retention.purgeNow();
        } catch (DataMaintenance.Busy e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    public record Status(BackupService.Status backup, int retentionDays, int attachmentsDays,
        StorageHealth.Snapshot storage, String executionIsolation, boolean isolated, boolean degraded,
        String degradedReason) {
    }
}
