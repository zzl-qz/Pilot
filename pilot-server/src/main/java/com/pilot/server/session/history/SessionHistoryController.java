package com.pilot.server.session.history;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.session.EventSubscription;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.events.AsyncEventStream;
import com.pilot.server.session.events.SessionEventTransport;
import com.pilot.server.session.lookup.SessionLookup;
import com.pilot.server.workspace.ChangeService;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionHistoryController {
    private static final int MAX_TURN_ALIGN_EXTRA = 120;
    private static final int MAX_MIN_TURNS_EVENTS = 320;
    private static final int MAX_EMBEDDED_RUN_CHANGES = 20;
    private static final Set<String> TURN_BOUNDARY_TYPES = Set.of("run.started", "user.message");
    private static final Set<String> RUN_END_TYPES =
        Set.of("run.completed", "run.ended", "run.failed", "run.cancelled");

    private final SessionService sessions;
    private final ObjectMapper objectMapper;
    private final SessionEventTransport eventTransport;
    private final ObjectProvider<RunSnapshotService> snapshotsProvider;

    @Value("${pilot.events.queue-bytes:8388608}")
    private long streamBytes = 8L * 1024 * 1024;
    @Value("${pilot.events.send-timeout-ms:30000}")
    private long streamTimeout = 30_000;

    /**
     * 历史事件分页：界面首屏只取尾部一页，避免长会话一次性回放上千条事件。
     *
     * @param before            只返回序号小于它的历史，便于向上翻页
     * @param after             只返回序号大于它的历史，便于窗口回收后向下翻页
     * @param around            锚点直达：一次取回该序号前后上下文窗口（供搜索结果与远距书签 1 跳直达）
     * @param alignTurns        向更早方向对齐到最近的 {@code run.started}/{@code user.message} 边界（带安全上限），防止将单个 Turn 拦腰截断
     * @param includeRunChanges 批量随页附带本页已保存快照的变更统计摘要（去 diff 正文），消除前端 N+1 请求与二次撑高
     */
    @GetMapping("/{id}/history")
    public HistoryResponse history(@PathVariable String id,
        @RequestParam(name = "limit", defaultValue = "60") int limit,
        @RequestParam(name = "before", required = false) Long before,
        @RequestParam(name = "after", required = false) Long after,
        @RequestParam(name = "around", required = false) Long around,
        @RequestParam(name = "compactTools", defaultValue = "false") boolean compactTools,
        @RequestParam(name = "alignTurns", defaultValue = "false") boolean alignTurns,
        @RequestParam(name = "minTurns", defaultValue = "0") int minTurns,
        @RequestParam(name = "includeRunChanges", defaultValue = "false") boolean includeRunChanges) {
        SessionLookup.require(sessions, id);
        int cursorParams = (before != null ? 1 : 0) + (after != null ? 1 : 0) + (around != null ? 1 : 0);
        if (cursorParams > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before、after 和 around 不能同时使用");
        }
        if (around != null && around < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "around 必须大于等于 1");
        }
        int capped = Math.max(1, Math.min(limit, 500));
        long revision = sessions.historyRevision(id);
        List<EventEnvelope> page;
        if (around != null) {
            int beforeQuota = Math.max(1, capped / 2);
            int afterQuota = Math.max(0, capped - beforeQuota);
            List<EventEnvelope> head = sessions.eventsPage(id, around + 1, beforeQuota);
            List<EventEnvelope> tail = afterQuota > 0 ? sessions.eventsPageAfter(id, around, afterQuota) : List.of();
            if (tail.size() < afterQuota && !head.isEmpty() && head.getFirst().sequence() > 1) {
                int extra = afterQuota - tail.size();
                List<EventEnvelope> moreHead = sessions.eventsPage(id, head.getFirst().sequence(), extra);
                List<EventEnvelope> combined = new ArrayList<>(moreHead.size() + head.size() + tail.size());
                combined.addAll(moreHead);
                combined.addAll(head);
                combined.addAll(tail);
                page = combined;
            } else if (head.size() < beforeQuota && !tail.isEmpty()) {
                int extra = beforeQuota - head.size();
                List<EventEnvelope> moreTail = sessions.eventsPageAfter(id, tail.getLast().sequence(), extra);
                List<EventEnvelope> combined = new ArrayList<>(head.size() + tail.size() + moreTail.size());
                combined.addAll(head);
                combined.addAll(tail);
                combined.addAll(moreTail);
                page = combined;
            } else {
                List<EventEnvelope> combined = new ArrayList<>(head.size() + tail.size());
                combined.addAll(head);
                combined.addAll(tail);
                page = combined;
            }
        } else if (after != null) {
            page = sessions.eventsPageAfter(id, after, capped);
        } else {
            page = sessions.eventsPage(id, before, capped);
            if (minTurns > 0 && !page.isEmpty()) {
                page = expandToMinTurns(id, page, Math.min(minTurns, 20), capped);
            }
        }
        if (alignTurns && after == null && !page.isEmpty() && page.getFirst().sequence() > 1
            && !TURN_BOUNDARY_TYPES.contains(page.getFirst().type())) {
            page = alignToTurnStart(id, page);
        }
        long latestSequence = sessions.latestSequence(id);
        boolean hasNewer = !page.isEmpty() && page.getLast().sequence() < latestSequence;
        boolean hasMore = after != null ? hasNewer : !page.isEmpty() && page.getFirst().sequence() > 1;
        int total = (int)Math.min(sessions.eventCount(id), Integer.MAX_VALUE);
        List<?> responseEvents = compactTools ? page.stream().map(event -> {
            if (!(event.payload() instanceof ToolCompleted completed)) {
                return event;
            }
            String summary = completed.resultSummary();
            if (summary == null || summary.isEmpty()) {
                return event;
            }
            int end = summary.offsetByCodePoints(0, Math.min(80, summary.codePointCount(0, summary.length())));
            String preview = summary.substring(0, end).lines().findFirst().orElse("");
            var compact = new EventEnvelope(event.sequence(), event.timestamp(), event.type(), event.sessionId(),
                event.runId(), new ToolCompleted(completed.callId(), preview, completed.latencyMs()), event.version(),
                event.previewId(), event.previewOffset());
            return objectMapper.valueToTree(compact).asObject().put("detailAvailable", true);
        }).toList() : page;
        Map<String, ChangeService.RunChangeReport> runChanges =
            includeRunChanges ? collectRunChangeSummaries(page) : null;
        if (revision != sessions.historyRevision(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话历史已变化，请重新加载");
        }
        return new HistoryResponse(responseEvents, hasMore, hasNewer, total, revision, runChanges);
    }

    private List<EventEnvelope> expandToMinTurns(String sessionId, List<EventEnvelope> initial, int targetTurns,
        int chunkLimit) {
        List<EventEnvelope> current = initial;
        int boundaries = countTurnBoundaries(current);
        int maxEvents = Math.max(current.size(), MAX_MIN_TURNS_EVENTS);
        while (boundaries < targetTurns && !current.isEmpty() && current.getFirst().sequence() > 1
            && current.size() < maxEvents) {
            int remaining = maxEvents - current.size();
            int fetch = Math.min(chunkLimit, remaining);
            List<EventEnvelope> older = sessions.eventsPage(sessionId, current.getFirst().sequence(), fetch);
            if (older.isEmpty()) {
                break;
            }
            List<EventEnvelope> merged = new ArrayList<>(older.size() + current.size());
            merged.addAll(older);
            merged.addAll(current);
            current = merged;
            boundaries += countTurnBoundaries(older);
        }
        return current;
    }

    private int countTurnBoundaries(List<EventEnvelope> events) {
        int count = 0;
        for (EventEnvelope event : events) {
            if (TURN_BOUNDARY_TYPES.contains(event.type())) {
                count++;
            }
        }
        return count;
    }

    private List<EventEnvelope> alignToTurnStart(String sessionId, List<EventEnvelope> page) {
        List<EventEnvelope> lookback = sessions.eventsPage(sessionId, page.getFirst().sequence(), MAX_TURN_ALIGN_EXTRA);
        for (int i = lookback.size() - 1; i >= 0; i--) {
            if (TURN_BOUNDARY_TYPES.contains(lookback.get(i).type())) {
                List<EventEnvelope> aligned = new ArrayList<>((lookback.size() - i) + page.size());
                aligned.addAll(lookback.subList(i, lookback.size()));
                aligned.addAll(page);
                return aligned;
            }
        }
        return page;
    }

    private Map<String, ChangeService.RunChangeReport> collectRunChangeSummaries(List<EventEnvelope> page) {
        RunSnapshotService snapshots = snapshotsProvider.getIfAvailable();
        if (snapshots == null || page.isEmpty()) {
            return Map.of();
        }
        Set<String> runIds = new LinkedHashSet<>();
        for (EventEnvelope event : page) {
            if (event.runId() != null && !event.runId().isBlank() && RUN_END_TYPES.contains(event.type())) {
                runIds.add(event.runId());
                if (runIds.size() >= MAX_EMBEDDED_RUN_CHANGES) {
                    break;
                }
            }
        }
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ChangeService.RunChangeReport> summaries = new LinkedHashMap<>();
        for (String runId : runIds) {
            try {
                ChangeService.RunChangeReport report = snapshots.changes(runId);
                if (report == null) {
                    continue;
                }
                List<ChangeService.RunFileChange> files = report.files().stream()
                    .map(file -> file.diff() == null || file.diff().isEmpty() ? file
                        : new ChangeService.RunFileChange(file.path(), file.status(), file.added(), file.removed(),
                            null, file.note(), file.countsKnown()))
                    .toList();
                summaries.put(runId,
                    new ChangeService.RunChangeReport(report.runId(), report.workspace(), report.gitRepository(), files,
                        report.added(), report.removed(), report.truncated(), report.error(), report.snapshot()));
            } catch (RuntimeException ignored) {
                // 单轮快照损坏不影响整页历史读取；前端会按需回退单轮查询
            }
        }
        return summaries;
    }

    /**
     * @param hasMore    向更早方向（after 模式下为更新方向）是否还有事件
     * @param hasNewer   当前页尾部之后是否还有更新事件（锚点直达 / 历史回看时用于提示「回到最新」）
     * @param total      该会话的事件总数
     * @param runChanges 随页附带的本页运行改动摘要（未请求时省略）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryResponse(List<?> events, boolean hasMore, boolean hasNewer, int total, long historyRevision,
        Map<String, ChangeService.RunChangeReport> runChanges) {
        public HistoryResponse(List<?> events, boolean hasMore, int total, long historyRevision) {
            this(events, hasMore, false, total, historyRevision, null);
        }
    }

    @GetMapping("/stream-metrics")
    public Map<String, Object> streamMetrics() {
        return Map.of("delivery", sessions.streamMetrics(), "transport", eventTransport.metrics());
    }

    @GetMapping("/{id}/events/{sequence}")
    public Map<String, Object> referencedEvent(@PathVariable String id, @PathVariable long sequence,
        @RequestParam long revision) {
        SessionLookup.require(sessions, id);
        if (sessions.historyRevision(id) != revision) {
            throw new SessionService.HistoryChangedException();
        }
        var page = sessions.replayPage(id, sequence - 1, sequence, 1);
        if (page.isEmpty() || page.getFirst().sequence() != sequence) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "事件已不存在");
        }
        var event = page.getFirst();
        if (objectMapper.writeValueAsBytes(event).length > 8 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单条事件超过 8 MiB，请检查事件数据");
        }
        if (sessions.historyRevision(id) != revision) {
            throw new SessionService.HistoryChangedException();
        }
        return Map.of("event", event, "historyRevision", revision);
    }

    /**
     * SSE 事件流（服务端 → 客户端单向推送）。
     * 支持 {@code Last-Event-ID} 请求头或 {@code after} 查询参数作为游标，用于断线重连。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void events(@PathVariable String id, @RequestParam(name = "after", required = false) Long after,
        @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
        @RequestParam(name = "revision", required = false) Long expectedRevision,
        @RequestParam(name = "protocol", defaultValue = "1") int protocol, HttpServletRequest request,
        HttpServletResponse response) throws IOException {
        SessionLookup.require(sessions, id);
        long cursor = Math.max(0, after != null ? after : lastEventId != null ? lastEventId : 0L);
        long revision = sessions.historyRevision(id);
        AtomicReference<EventSubscription> subscription = new AtomicReference<>();
        var registered = new CompletableFuture<Void>();
        AtomicReference<AsyncEventStream> transport = new AtomicReference<>();
        Runnable finish = () -> finishStream(transport.get(), id, revision, subscription.get());
        var stream = new AsyncEventStream(eventTransport, request, response, streamBytes,
            Duration.ofMillis(streamTimeout), () -> {
                var current = subscription.get();
                if (current != null) {
                    current.close();
                }
            }, () -> {
                if (sessions.historyRevision(id) != revision) {
                    finish.run();
                }
            });
        transport.set(stream);
        if (expectedRevision != null && expectedRevision != revision) {
            sendControl(stream, "stream.reset", id, Map.of("historyRevision", revision));
            stream.finish();
            return;
        }
        sendControl(stream, "stream.ready", id, Map.of("historyRevision", revision));
        try {
            var handle = sessions.subscribe(id, cursor, revision, envelope -> {
                if (!stream.send(envelope.type(), Long.toString(envelope.sequence()),
                    objectMapper.writeValueAsString(envelope))) {
                    throw new IllegalStateException("事件流已关闭");
                }
            }, protocol >= 2 ? reference -> {
                if (!sendControl(stream, "stream.event", id,
                    Map.of("sequence", reference.sequence(), "historyRevision", revision))) {
                    throw new IllegalStateException("事件流已关闭");
                }
            } : null, protocol >= 2 ? snapshot -> {
                if (!sendControl(stream, "stream.preview", id, objectMapper.convertValue(snapshot, Map.class))) {
                    throw new IllegalStateException("事件流已关闭");
                }
            } : null, () -> registered.thenRun(finish));
            subscription.set(handle);
            registered.complete(null);
            if (stream.isClosed()) {
                handle.close();
            }
            if (handle.isClosed()) {
                finish.run();
            }
        } catch (SessionService.HistoryChangedException failure) {
            finish.run();
        } catch (RuntimeException failure) {
            sendControl(stream, "stream.error", id, Map.of("message", "无法建立事件订阅，请稍后刷新"));
            stream.finish();
        } finally {
            registered.complete(null);
        }
    }

    private void finishStream(AsyncEventStream stream, String id, long revision, EventSubscription subscription) {
        if (stream == null || stream.isClosed()) {
            return;
        }
        try {
            if (sessions.find(id).isPresent() && sessions.historyRevision(id) != revision) {
                sendControl(stream, "stream.reset", id, Map.of("historyRevision", sessions.historyRevision(id)));
            } else if (subscription != null && "event_too_large".equals(subscription.closeReason())) {
                sendControl(stream, "stream.error", id, Map.of("message", "单条历史事件超过 8 MiB，已停止自动恢复，请检查事件数据"));
            } else if (subscription != null && "delivery_error".equals(subscription.closeReason())) {
                sendControl(stream, "stream.error", id, Map.of("message", "历史事件交付失败，已停止自动恢复，请刷新或检查服务端记录"));
            }
        } finally {
            stream.finish();
        }
    }

    private boolean sendControl(AsyncEventStream stream, String type, String id, Map<String, ?> payload) {
        return stream.send(type, null,
            objectMapper.writeValueAsString(Map.of("type", type, "sessionId", id, "payload", payload)));
    }
}
