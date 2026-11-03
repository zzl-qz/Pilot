package com.pilot.server.controller;

import com.pilot.server.results.ResultInboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/results")
public class ResultInboxController {
    private final ResultInboxService service;
    public ResultInboxController(ResultInboxService service) { this.service = service; }
    @GetMapping public ResultInboxService.Page list() { return service.list(); }
    @GetMapping("/{id}") public ResultInboxService.Detail detail(@PathVariable String id) { return service.detail(id); }
    public record Mark(String revision, String action) { }
    @PostMapping("/{id}/receipt") public ResultInboxService.Item mark(@PathVariable String id, @RequestBody Mark mark) {
        if (mark == null || mark.revision() == null || mark.action() == null) throw new IllegalArgumentException("缺少结果版本或操作");
        return service.mark(id, mark.revision(), mark.action());
    }
    @GetMapping("/research/{sessionId}") public Object research(@PathVariable String sessionId) { return service.sessionResearch(sessionId); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }
}
