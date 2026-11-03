package com.pilot.server.controller;

import com.pilot.server.schedule.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleService schedules;
    public ScheduleController(ScheduleService schedules) { this.schedules = schedules; }
    @GetMapping public List<ScheduledTask> list() { return schedules.list(); }
    @PostMapping public ScheduledTask create(@RequestBody ScheduleService.Create request) { return schedules.create(request); }
    public record Control(String action, Instant runAt) { }
    @PostMapping("/{id}/control") public ScheduledTask control(@PathVariable String id, @RequestBody Control body) {
        if (body == null || body.action() == null) throw new IllegalArgumentException("缺少操作");
        return schedules.control(id, body.action(), body.runAt());
    }
    @ExceptionHandler({IllegalArgumentException.class, java.time.DateTimeException.class})
    public ResponseEntity<Map<String, String>> invalid(RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage())); }
}
