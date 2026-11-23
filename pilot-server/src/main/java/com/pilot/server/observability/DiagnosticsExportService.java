package com.pilot.server.observability;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.session.SessionService;
import com.pilot.core.util.SecretScrubber;
import com.pilot.server.run.RecoveryReportService;
import com.pilot.server.run.RunStateTracker;

import tools.jackson.databind.json.JsonMapper;

import lombok.RequiredArgsConstructor;

/**
 * 脱敏诊断包导出服务：汇总运行环境、启动恢复状态、最近脱敏事件与失败请求记录。
 *
 * <p>所有文本字段均经 {@link Redaction#text(String)} 截断脱敏，最终序列化 JSON 再经 {@link SecretScrubber#scrub(String)}
 * 做兜底扫描，避免任何嵌套字段携带 API Key、令牌或密码出站。
 */
@Component
@RequiredArgsConstructor
public class DiagnosticsExportService {
    static final int MAX_RECENT_EVENTS = 200;
    static final int MAX_FAILED_REQUESTS = 50;
    private static final DateTimeFormatter FILE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public record ExportResult(String filename, byte[] payload) {
    }

    public record ExportBundle(Instant exportedAt, String appVersion, String os, String javaVersion, String instanceId,
        String sessionId, List<SanitizedEvent> recentEvents, List<FailedRequest> failedRequests,
        SanitizedRecovery recovery, boolean sanitized) {
    }

    public record SanitizedEvent(long sequence, Instant timestamp, String type, String runId, String summary) {
    }

    public record FailedRequest(long sequence, Instant timestamp, String runId, String kind, String code,
        String message, Integer attempt, Long delayMs) {
    }

    public record SanitizedRecovery(Instant recoveredAt, String instanceId, String note, boolean completed,
        List<SanitizedRecoveryEntry> entries) {
    }

    public record SanitizedRecoveryEntry(String runId, String sessionId, String step, String lastSuccessfulStep,
        String nextAction, String workspace, String leaseState, Instant updatedAt) {
    }

    private final SessionService sessions;
    private final RecoveryReportService recovery;
    private final RunStateTracker runStates;
    private final JsonMapper mapper;

    public ExportResult export(String sessionId) {
        Instant now = Instant.now();
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        List<EventEnvelope> allEvents = normalizedSessionId == null ? List.of() : sessions.events(normalizedSessionId);
        List<SanitizedEvent> recentEvents = extractRecentEvents(allEvents);
        List<FailedRequest> failedRequests = extractFailedRequests(allEvents);
        SanitizedRecovery recoveryView = sanitizeRecovery(recovery.latest());
        ExportBundle bundle = new ExportBundle(now, resolveAppVersion(), resolveOs(),
            System.getProperty("java.version", "unknown"), Redaction.text(runStates.instanceId()), normalizedSessionId,
            recentEvents, failedRequests, recoveryView, true);
        String json = SecretScrubber.scrub(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle));
        String filename = "pilot-diagnostics-" + FILE_TIMESTAMP.format(now) + ".json";
        return new ExportResult(filename, json.getBytes(StandardCharsets.UTF_8));
    }

    private List<SanitizedEvent> extractRecentEvents(List<EventEnvelope> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, events.size() - MAX_RECENT_EVENTS);
        List<SanitizedEvent> result = new ArrayList<>(events.size() - start);
        for (int i = start; i < events.size(); i++) {
            EventEnvelope event = events.get(i);
            result.add(new SanitizedEvent(event.sequence(), event.timestamp(), event.type(),
                Redaction.text(event.runId()), sanitizePayload(event)));
        }
        return result;
    }

    private List<FailedRequest> extractFailedRequests(List<EventEnvelope> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<FailedRequest> matches = new ArrayList<>();
        for (EventEnvelope event : events) {
            if (event.payload() instanceof RunFailed failed) {
                matches.add(new FailedRequest(event.sequence(), event.timestamp(), Redaction.text(event.runId()),
                    "run.failed", Redaction.text(failed.code()), Redaction.text(failed.message()), null, null));
            } else if (event.payload() instanceof RunRetrying retrying) {
                matches.add(new FailedRequest(event.sequence(), event.timestamp(), Redaction.text(event.runId()),
                    "run.retrying", "RETRYING", Redaction.text(retrying.reason()), retrying.attempt(),
                    retrying.delayMs()));
            }
        }
        if (matches.size() <= MAX_FAILED_REQUESTS) {
            return matches;
        }
        return List.copyOf(matches.subList(matches.size() - MAX_FAILED_REQUESTS, matches.size()));
    }

    private SanitizedRecovery sanitizeRecovery(RecoveryReportService.Snapshot snapshot) {
        List<SanitizedRecoveryEntry> entries = snapshot.entries().stream()
            .map(entry -> new SanitizedRecoveryEntry(Redaction.text(entry.runId()), Redaction.text(entry.sessionId()),
                Redaction.text(entry.step()), Redaction.text(entry.lastSuccessfulStep()),
                Redaction.text(entry.nextAction()), Redaction.text(entry.workspace()),
                Redaction.text(entry.leaseState()), entry.updatedAt()))
            .toList();
        return new SanitizedRecovery(snapshot.recoveredAt(), Redaction.text(runStates.instanceId()),
            Redaction.text(snapshot.note()), snapshot.completed(), entries);
    }

    private String sanitizePayload(EventEnvelope event) {
        try {
            return Redaction.text(mapper.writeValueAsString(event.payload()));
        } catch (Exception ignored) {
            return Redaction.text(String.valueOf(event.payload()));
        }
    }

    private static String resolveAppVersion() {
        Package pkg = DiagnosticsExportService.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private static String resolveOs() {
        String name = System.getProperty("os.name", "unknown");
        String version = System.getProperty("os.version", "");
        String arch = System.getProperty("os.arch", "");
        return (name + " " + version + (arch.isBlank() ? "" : " (" + arch + ")")).trim();
    }
}
