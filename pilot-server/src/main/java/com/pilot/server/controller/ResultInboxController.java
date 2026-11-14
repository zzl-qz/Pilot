package com.pilot.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.common.web.ApiErrors;
import com.pilot.server.results.ResultInboxService;

@RestController
@RequestMapping("/api/results")
public class ResultInboxController {
    private final ResultInboxService service;

    public ResultInboxController(ResultInboxService service) {
        this.service = service;
    }

    @GetMapping
    public ResultInboxService.Page list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ResultInboxService.Detail detail(@PathVariable String id) {
        return service.detail(id);
    }

    public record Mark(String revision, String action) {
    }

    @PostMapping("/{id}/receipt")
    public ResultInboxService.Item mark(@PathVariable String id, @RequestBody Mark mark) {
        if (mark == null || mark.revision() == null || mark.action() == null) {
            throw new IllegalArgumentException("缺少结果版本或操作");
        }
        return service.mark(id, mark.revision(), mark.action());
    }

    @GetMapping("/research/{sessionId}")
    public Object research(@PathVariable String sessionId) {
        return service.sessionResearch(sessionId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ApiErrors.badRequest(e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ApiErrors.conflict(e);
    }
}
