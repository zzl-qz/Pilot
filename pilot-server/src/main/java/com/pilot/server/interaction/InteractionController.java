package com.pilot.server.interaction;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/** 全局待审批、待选择与运行提醒接口。 */
@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
public class InteractionController {
    private final InteractionHub hub;

    @GetMapping("/pending")
    public InteractionHub.PendingSnapshot pending() {
        return hub.snapshot();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe();
    }
}
