package com.pilot.server.observability;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.run.RecoveryReportService;
import com.pilot.server.run.RunStateTracker;
import com.pilot.server.session.lookup.SessionLookup;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.validation.WorkspaceFingerprint;

import lombok.RequiredArgsConstructor;

/**
 * 任务诊断与校验证据接口。
 *
 * <p>诊断视图回答的是运维问题：卡在哪、上次成功步骤、正在重试什么、花费多少、重启后能否接续。
 * 所有文本都经过脱敏，不返回模型密钥或用户凭据。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DiagnosticsController {
    private final SessionService sessions;
    private final TaskDiagnosticsService diagnostics;
    private final DiagnosticsExportService exporter;
    private final ValidationLedger validations;
    private final RecoveryReportService recovery;
    private final RunStateTracker runStates;

    @GetMapping(value = "/diagnostics/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            requireSession(sessionId.trim());
        }
        DiagnosticsExportService.ExportResult result = exporter.export(sessionId);
        return ResponseEntity.ok().contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(result.filename()).build().toString())
            .body(result.payload());
    }

    @GetMapping("/sessions/{id}/diagnostics")
    public TaskDiagnosticsService.Diagnostics diagnose(@PathVariable String id) {
        requireSession(id);
        return diagnostics.diagnose(id);
    }

    /** 最近一次校验证据是否覆盖当前产物；完成声明必须能对应这里的结论。 */
    @GetMapping("/sessions/{id}/validation")
    public ValidationView validation(@PathVariable String id) {
        Session session = requireSession(id);
        List<ValidationEvidence> recent = validations.recent(id);
        ValidationEvidence latest = recent.isEmpty() ? null : recent.getLast();
        String current = latest == null ? "" : WorkspaceFingerprint.of(workspaceOf(session), latest.files());
        ValidationLedger.Report report = validations.report(id, current);
        return new ValidationView(report, recent);
    }

    @GetMapping("/recovery")
    public RecoveryReportService.Snapshot recovery() {
        RecoveryReportService.Snapshot snapshot = recovery.latest();
        return new RecoveryReportService.Snapshot(snapshot.recoveredAt(), runStates.instanceId(), snapshot.entries(),
            snapshot.note(), snapshot.completed());
    }

    public record ValidationView(ValidationLedger.Report report, List<ValidationEvidence> recent) {
    }

    private Session requireSession(String id) {
        return SessionLookup.require(sessions, id);
    }

    private static Path workspaceOf(Session session) {
        String configured = session.workspace();
        return configured == null || configured.isBlank() ? Path.of(System.getProperty("user.dir", "."))
            : Path.of(configured).toAbsolutePath().normalize();
    }
}
