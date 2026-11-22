package com.pilot.server.coordination;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.coordination.CoordinationScopeResolver;
import com.pilot.core.coordination.ProjectCoordinationScope;
import com.pilot.core.coordination.WorkspaceCoordinationEvent;
import com.pilot.core.coordination.WorkspaceCoordinationEventBus;
import com.pilot.core.coordination.WorkspaceEventSubscription;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.session.events.AsyncEventStream;
import com.pilot.server.session.events.SessionEventTransport;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 工作区协调事件流（服务端 → 客户端单向推送）。
 *
 * <p>与单会话 SSE 的协议同构：{@code id} 是事件序号，{@code event} 是事件类型，支持 {@code Last-Event-ID}
 * 请求头或 {@code after} 查询参数作为游标做断线续传；连接关闭、写超时、字节预算与背压都由
 * {@link AsyncEventStream} 统一处理，这里只负责「按协调范围过滤 + 序号续传 + 缓存缺口告知」。
 *
 * <p>订阅范围按入口目录解析成 {@link ProjectCoordinationScope}：主目录与项目全部文件夹都参与，
 * 不同项目之间互不可见。事件只带定位信息与短摘要，不包含文件内容或令牌。
 */
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class WorkspaceCoordinationStreamController {

    private final SessionRepository sessions;
    private final CoordinationScopeResolver scopes;
    private final WorkspaceCoordinationEventBus events;
    private final SessionEventTransport streams;
    private final ObjectMapper objectMapper;

    @Value("${pilot.workspace-events.buffer-bytes:1048576}")
    private long streamBytes = 1024 * 1024;
    @Value("${pilot.workspace-events.send-timeout-ms:30000}")
    private long streamTimeout = 30_000;

    /** 事件流的线上格式：序号用于去重与续传，字段与 {@link WorkspaceCoordinationEvent} 一致。 */
    public record WorkspaceEventFrame(String type, long sequence, String timestamp, String projectId,
        String sessionId, String directory, String subject, Map<String, String> details) {

        static WorkspaceEventFrame of(WorkspaceCoordinationEvent event) {
            return new WorkspaceEventFrame(event.wireType(), event.sequence(), event.timestamp().toString(),
                event.projectId(), event.sessionId(), event.directory(), event.subject(), event.details());
        }
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void events(@RequestParam String workspace, @RequestParam(name = "after", required = false) Long after,
        @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId, HttpServletRequest request,
        HttpServletResponse response) throws IOException {
        String key = new PathValidator(Path.of(workspace)).root().toString();
        ProjectCoordinationScope scope = scopes.scopeForWorkspace(key, sessions.list());
        long cursor = Math.max(0, after != null ? after : lastEventId != null ? lastEventId : 0L);
        AtomicReference<WorkspaceEventSubscription> subscription = new AtomicReference<>();
        var stream = new AsyncEventStream(streams, request, response, streamBytes,
            Duration.ofMillis(streamTimeout), () -> {
                WorkspaceEventSubscription current = subscription.get();
                if (current != null) {
                    current.close();
                }
            }, () -> {
            });
        long earliest = events.earliestRetainedSequence();
        long latest = events.latestSequence();
        if (cursor > 0 && cursor + 1 < earliest) {
            sendControl(stream, "workspace.reset", Map.of("earliestSequence", earliest, "latestSequence", latest,
                "reason", "cursor_expired"));
            stream.finish();
            return;
        }
        sendControl(stream, "workspace.ready", ready(scope, earliest, latest));
        try {
            WorkspaceEventSubscription handle = events.subscribe(scope, cursor, event -> {
                if (!stream.send(event.wireType(), Long.toString(event.sequence()), toJson(event))) {
                    stream.terminate("send_failed");
                    throw new IllegalStateException("工作区事件流已关闭");
                }
            });
            subscription.set(handle);
            if (stream.isClosed()) {
                handle.close();
            }
        } catch (IllegalStateException failure) {
            sendControl(stream, "workspace.error",
                Map.of("message", failure.getMessage() == null ? "无法建立工作区事件订阅" : failure.getMessage()));
            stream.finish();
        }
    }

    /** 订阅与传输的实时指标，供看板排查连接与丢弃情况。 */
    @GetMapping("/events/metrics")
    public Map<String, Object> metrics() {
        return Map.of("events", events.metrics(), "transport", streams.metrics());
    }

    private Map<String, Object> ready(ProjectCoordinationScope scope, long earliest, long latest) {
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("projectId", scope.projectId() == null ? "" : scope.projectId());
        ready.put("directories", scope.directories().stream().map(Path::toString).toList());
        ready.put("earliestSequence", earliest);
        ready.put("latestSequence", latest);
        return ready;
    }

    private String toJson(WorkspaceCoordinationEvent event) {
        return objectMapper.writeValueAsString(WorkspaceEventFrame.of(event));
    }

    private boolean sendControl(AsyncEventStream stream, String type, Map<String, ?> payload) {
        return stream.send(type, null, objectMapper.writeValueAsString(Map.of("type", type, "payload", payload)));
    }
}
