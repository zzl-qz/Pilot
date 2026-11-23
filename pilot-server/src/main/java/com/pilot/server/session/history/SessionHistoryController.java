package com.pilot.server.session.history;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.session.EventSubscription;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.events.AsyncEventStream;
import com.pilot.server.session.events.SessionEventTransport;
import com.pilot.server.session.lookup.SessionLookup;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionHistoryController {
    private final SessionService sessions;
    private final ObjectMapper objectMapper;
    private final SessionEventTransport eventTransport;

    @Value("${pilot.events.queue-bytes:8388608}")
    private long streamBytes = 8L * 1024 * 1024;
    @Value("${pilot.events.send-timeout-ms:30000}")
    private long streamTimeout = 30_000;

    /**
     * 历史事件分页：界面首屏只取尾部一页，避免长会话一次性回放上千条事件。
     *
     * @param before 只返回序号小于它的历史，便于向上翻页
     * @param after  只返回序号大于它的历史，便于窗口回收后向下翻页
     */
    @GetMapping("/{id}/history")
    public HistoryResponse history(@PathVariable String id,
        @RequestParam(name = "limit", defaultValue = "60") int limit,
        @RequestParam(name = "before", required = false) Long before,
        @RequestParam(name = "after", required = false) Long after,
        @RequestParam(name = "compactTools", defaultValue = "false") boolean compactTools) {
        SessionLookup.require(sessions, id);
        if (before != null && after != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before 和 after 不能同时使用");
        }
        int capped = Math.max(1, Math.min(limit, 500));
        long revision = sessions.historyRevision(id);
        List<EventEnvelope> page =
            after != null ? sessions.eventsPageAfter(id, after, capped) : sessions.eventsPage(id, before, capped);
        boolean hasMore =
            after != null ? !page.isEmpty() && page.get(page.size() - 1).sequence() < sessions.latestSequence(id)
                : !page.isEmpty() && page.get(0).sequence() > 1;
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
        if (revision != sessions.historyRevision(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话历史已变化，请重新加载");
        }
        return new HistoryResponse(responseEvents, hasMore, total, revision);
    }

    /**
     * @param hasMore 是否还有更早的事件
     * @param total   该会话的事件总数
     */
    public record HistoryResponse(List<?> events, boolean hasMore, int total, long historyRevision) {
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
