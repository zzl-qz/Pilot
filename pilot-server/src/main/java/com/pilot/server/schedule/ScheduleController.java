package com.pilot.server.schedule;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.common.web.ApiErrors;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleService schedules;

    public ScheduleController(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public List<ScheduledTask> list() {
        return schedules.list();
    }

    @PostMapping
    public ScheduledTask create(@RequestBody ScheduleService.Create request) {
        return schedules.create(request);
    }

    @GetMapping("/{id}/handoff")
    public Object handoffPreview(@PathVariable String id) {
        return schedules.handoffPreview(id);
    }

    public record Handoff(String revision) {
    }

    @PostMapping("/{id}/handoff")
    public ScheduledTask handoff(@PathVariable String id, @RequestBody Handoff body) {
        return schedules.handoff(id, body == null ? null : body.revision());
    }

    public record Control(String action, Instant runAt) {
    }

    @PostMapping("/{id}/control")
    public ScheduledTask control(@PathVariable String id, @RequestBody Control body) {
        if (body == null || body.action() == null) {
            throw new IllegalArgumentException("缺少操作");
        }
        return schedules.control(id, body.action(), body.runAt());
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    public ResponseEntity<Map<String, String>> invalid(RuntimeException e) {
        return ApiErrors.badRequest(e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ApiErrors.conflict(e);
    }
}
