package com.pilot.server.controller;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.session.Run;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.server.session.RunStarter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private static final String DEFAULT_TITLE = "未命名会话";

    private final SessionService sessions;
    private final RunStarter runStarter;
    private final ObjectMapper objectMapper;

    public SessionController(SessionService sessions, RunStarter runStarter, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.runStarter = runStarter;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null || request.title() == null || request.title().isBlank()
                ? DEFAULT_TITLE
                : request.title();
        Session session = sessions.createSession(title);
        return SessionResponse.of(session, 0);
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable String id) {
        Session session = requireSession(id);
        return SessionResponse.of(session, sessions.events(id).size());
    }

    @PostMapping("/{id}/messages")
    public MessageResponse sendMessage(@PathVariable String id, @RequestBody MessageRequest request) {
        requireSession(id);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        Run run = sessions.sendMessage(id, request.content());
        runStarter.start(id, run.runId(), request.content());
        return new MessageResponse(id, run.runId());
    }

    /**
     * SSE 事件流（服务端 → 客户端单向推送）。
     * 支持 {@code Last-Event-ID} 请求头或 {@code after} 查询参数作为游标，用于断线重连。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id,
                                             @RequestParam(name = "after", required = false) Long after,
                                             @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        requireSession(id);
        long cursor = after != null ? after : lastEventId != null ? lastEventId : 0L;
        var emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().comment("stream open: session=" + id + ", after=" + cursor));
            var subscription = sessions.subscribe(id, cursor, envelope -> send(emitter, envelope));
            emitter.onCompletion(subscription::close);
            emitter.onTimeout(subscription::close);
            emitter.onError(error -> subscription.close());
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private void send(SseEmitter emitter, EventEnvelope envelope) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(envelope.sequence()))
                    .name(envelope.type())
                    .data(objectMapper.writeValueAsString(envelope)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Session requireSession(String id) {
        return sessions.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
    }

    public record CreateSessionRequest(String title) { }

    public record MessageRequest(String content) { }

    public record MessageResponse(String sessionId, String runId) { }

    public record SessionResponse(String sessionId, String title, Instant createdAt, SessionStatus status, int eventCount) {
        static SessionResponse of(Session session, int eventCount) {
            return new SessionResponse(session.id(), session.title(), session.createdAt(), session.status(), eventCount);
        }
    }
}
