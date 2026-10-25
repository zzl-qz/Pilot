package com.pilot.server.controller;

import com.pilot.core.loop.AgentLoop;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ObjectProvider<AgentLoop> agentLoop;

    public ChatController(ObjectProvider<AgentLoop> agentLoop) {
        this.agentLoop = agentLoop;
    }

    @PostMapping
    public ChatReply chat(@RequestBody ChatRequest request) {
        AgentLoop loop = agentLoop.getIfAvailable();
        return new ChatReply(loop == null ? "AgentLoop is not configured yet." : loop.run(request.message()));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message) {
        var emitter = new SseEmitter();
        try {
            AgentLoop loop = agentLoop.getIfAvailable();
            emitter.send(SseEmitter.event().data(loop == null ? "AgentLoop is not configured yet." : loop.run(message)));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public record ChatRequest(String message) { }
    public record ChatReply(String reply) { }
}
