package com.pilot.server.observability;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.session.RunCheckpoint;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.session.RecoveryReportService;
import com.pilot.server.session.RunStateTracker;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.validation.WorkspaceFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 任务诊断视图：把「卡在哪、上次成功是什么、正在重试什么、花了多少、重启后能否接续」
 * 从多个存储里汇总成一份可直接读的回答，并对密钥类文本脱敏。
 */
@Component
public class TaskDiagnosticsService {

    /**
     * @param retryReason   正在重试的原因；没有重试时为空串
     * @param leaseState    租约状态；没有活跃检查点时为「无」
     * @param validationHint 完成声明的证据是否覆盖当前产物
     */
    public record Diagnostics(String sessionId, String sessionStatus, String activeRunId, String instanceId,
                              String phase, int step, String lastSuccessfulStep, String nextAction,
                              String leaseState, Long retryAttempt, Long retryDelayMs, String retryReason,
                              Long elapsedSeconds, int modelRequests, long inputTokens, long outputTokens,
                              long cachedInputTokens,
                              boolean usageExact, String lastToolSummary, List<CommandView> commands,
                              ValidationView validation, RecoveryView recovery,
                              String storageHealth, com.pilot.core.model.RequestTotals.Summary usageDetails) {
    }

    public record CommandView(String taskId, String command, String status, Integer exitCode,
                              Long runningSeconds) {
    }

    public record ValidationView(boolean covered, String reason, String candidateHash,
                                 String status, List<String> checks, Instant recordedAt, String runId) {
    }

    public record RecoveryView(String recoveredAt, String note, int count, String thisRunStartupState) {
    }

    private final SessionService sessions;
    private final RunStateTracker runStates;
    private final ValidationLedger validations;
    private final RecoveryReportService recovery;
    private final ObjectProvider<CommandTaskManager> commandTasks;

    public TaskDiagnosticsService(SessionService sessions, RunStateTracker runStates,
                                  ValidationLedger validations, RecoveryReportService recovery,
                                  ObjectProvider<CommandTaskManager> commandTasks) {
        this.sessions = sessions;
        this.runStates = runStates;
        this.validations = validations;
        this.recovery = recovery;
        this.commandTasks = commandTasks;
    }

    public Diagnostics diagnose(String sessionId) {
        Session session = sessions.find(sessionId).orElse(null);
        List<EventEnvelope> events = sessions.events(sessionId);
        RunCheckpoint checkpoint = runStates.current(sessionId).orElse(null);
        RunUsageUpdated usage = last(events, RunUsageUpdated.class);
        RunRetrying retrying = last(events, RunRetrying.class);
        ToolCompleted lastTool = last(events, ToolCompleted.class);
        RecoveryReportService.Snapshot recovered = recovery.latest();

        List<CommandView> commands = commandTasks.getIfAvailable() == null ? List.of()
                : commandTasks.getIfAvailable().list(sessionId).stream()
                .map(task -> new CommandView(task.taskId(), Redaction.text(task.command()), task.status(),
                        task.exitCode(), task.startedAt() == null ? null
                                : Duration.between(task.startedAt(), Instant.now()).toSeconds()))
                .toList();

        // 账本按写入顺序保留旧到新的证据，与 ValidationLedger.report 的选取保持一致。
        List<ValidationEvidence> recentEvidence = validations.recent(sessionId);
        ValidationEvidence latestEvidence = recentEvidence.isEmpty() ? null : recentEvidence.getLast();
        String currentCandidate = latestEvidence == null ? ""
                : WorkspaceFingerprint.of(workspaceOf(session), latestEvidence.files());
        ValidationLedger.Report report = validations.report(sessionId, currentCandidate);

        return new Diagnostics(sessionId,
                session == null ? "MISSING" : session.status().name(),
                checkpoint == null ? null : Redaction.text(checkpoint.runId()),
                runStates.instanceId(),
                checkpoint == null ? "" : checkpoint.phase(),
                checkpoint == null ? 0 : checkpoint.step(),
                Redaction.text(checkpoint == null ? "" : nullToEmpty(checkpoint.lastSuccessfulStep())),
                Redaction.text(checkpoint == null ? "" : nullToEmpty(checkpoint.nextAction())),
                leaseState(checkpoint),
                retrying == null ? null : (long) retrying.attempt(),
                retrying == null ? null : retrying.delayMs(),
                retrying == null ? "" : Redaction.text(retrying.reason()),
                checkpoint == null ? null : elapsed(checkpoint),
                usage == null ? 0 : usage.modelRequests(),
                usage == null ? 0 : usage.inputTokens(),
                usage == null ? 0 : usage.outputTokens(),
                usage == null ? 0 : usage.cachedInputTokens(),
                usage != null && usage.exact(),
                lastTool == null ? "" : Redaction.text(lastTool.resultSummary()),
                commands,
                new ValidationView(report.current(), report.reason(), Redaction.text(report.currentCandidateHash()),
                        latestEvidence == null ? "" : latestEvidence.status(),
                        latestEvidence == null ? List.of() : latestEvidence.checks().stream().map(Redaction::text).toList(),
                        latestEvidence == null ? null : latestEvidence.recordedAt(),
                        latestEvidence == null ? "" : latestEvidence.runId()),
                new RecoveryView(recovered.recoveredAt().toString(), Redaction.text(recovered.note()),
                        recovered.count(), recovered.completed() ? "已执行启动恢复" : "尚未执行启动恢复"),
                storageHealth(), usage == null ? null : usage.details());
    }

    private static String storageHealth() {
        com.pilot.server.maintenance.StorageHealth.Snapshot snapshot =
                com.pilot.server.maintenance.StorageHealth.snapshot();
        if (snapshot.healthy()) {
            return "正常";
        }
        return "降级：" + snapshot.issues().stream()
                .map(issue -> issue.area() + "（" + Redaction.text(issue.reason()) + "）")
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private String leaseState(RunCheckpoint checkpoint) {
        if (checkpoint == null) {
            return "无";
        }
        if (checkpoint.owner() == null) {
            return "已释放（" + checkpoint.status() + "）";
        }
        return "持有者 " + checkpoint.owner() + "，到期 " + checkpoint.leaseExpiresAt();
    }

    private static Long elapsed(RunCheckpoint checkpoint) {
        if (checkpoint.heartbeatAt() == null || checkpoint.heartbeatAt().toEpochMilli() == 0) {
            return null;
        }
        return Duration.between(checkpoint.heartbeatAt(), Instant.now()).toSeconds();
    }

    private static Path workspaceOf(Session session) {
        if (session == null || session.workspace() == null || session.workspace().isBlank()) {
            return Path.of(System.getProperty("user.dir", "."));
        }
        return Path.of(session.workspace()).toAbsolutePath().normalize();
    }

    private static <T extends com.pilot.core.event.SessionEvent> T last(List<EventEnvelope> events,
                                                                       Class<T> type) {
        for (int index = events.size() - 1; index >= 0; index--) {
            if (type.isInstance(events.get(index).payload())) {
                return type.cast(events.get(index).payload());
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
