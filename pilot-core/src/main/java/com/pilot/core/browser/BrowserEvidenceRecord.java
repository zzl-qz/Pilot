package com.pilot.core.browser;

import java.time.Instant;
import java.util.List;

/**
 * 浏览器操作的结构化宿主机证据，用于任务结果追踪、证据回看与截图反馈定位。
 */
public record BrowserEvidenceRecord(String id, String sessionId, String browserSessionId, String runId, String agentId,
    Instant timestamp, String action, String status, String url, String title, String summary, String screenshotPath,
    String downloadPath, String downloadName, String downloadMime, long downloadBytes, List<String> blockedOrigins,
    int consoleErrors, int failedRequests, String gateKind, String recordingPath) {

    public BrowserEvidenceRecord(String id, String sessionId, String browserSessionId, String runId, String agentId,
        Instant timestamp, String action, String status, String url, String title, String summary, String screenshotPath,
        String downloadPath, String downloadName, String downloadMime, long downloadBytes, List<String> blockedOrigins,
        int consoleErrors, int failedRequests, String gateKind) {
        this(id, sessionId, browserSessionId, runId, agentId, timestamp, action, status, url, title, summary,
            screenshotPath, downloadPath, downloadName, downloadMime, downloadBytes, blockedOrigins, consoleErrors,
            failedRequests, gateKind, "");
    }

    public BrowserEvidenceRecord {
        id = clean(id);
        sessionId = clean(sessionId);
        browserSessionId = clean(browserSessionId);
        runId = clean(runId);
        agentId = clean(agentId);
        timestamp = timestamp == null ? Instant.now() : timestamp;
        action = clean(action);
        status = clean(status);
        url = clean(url);
        title = clean(title);
        summary = clean(summary);
        screenshotPath = clean(screenshotPath);
        downloadPath = clean(downloadPath);
        downloadName = clean(downloadName);
        downloadMime = clean(downloadMime);
        downloadBytes = Math.max(0L, downloadBytes);
        blockedOrigins = blockedOrigins == null ? List.of() : List.copyOf(blockedOrigins);
        consoleErrors = Math.max(0, consoleErrors);
        failedRequests = Math.max(0, failedRequests);
        gateKind = clean(gateKind);
        recordingPath = clean(recordingPath);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
