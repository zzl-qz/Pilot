package com.pilot.server.schedule;

import java.time.DateTimeException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/automation")
public class AutomationController {
    private final RecurringScheduleService service;

    public AutomationController(RecurringScheduleService service) {
        this.service = service;
    }

    @GetMapping("/series")
    public Object list() {
        return service.list();
    }

    @PostMapping("/series")
    public Object create(@RequestBody RecurringScheduleService.Create input) {
        return service.create(input);
    }

    public record Control(long version, String action) {
    }

    @PostMapping("/series/{id}")
    public Object control(@PathVariable String id, @RequestBody Control input) {
        return service.control(id, input.version(), input.action());
    }

    public record TimeInput(String localTime, String timezone, String cron) {
    }

    @PostMapping("/times")
    public Object times(@RequestBody TimeInput input) {
        return service.times(input.localTime(), input.timezone(), input.cron());
    }

    @GetMapping("/templates")
    public Object templates() {
        return service.templates();
    }

    public record TemplateInput(String id, long version, String name, ScheduleService.Create task) {
    }

    @PostMapping("/templates")
    public Object template(@RequestBody TemplateInput input) {
        return service.saveTemplate(input.id(), input.version(), input.name(), input.task());
    }

    @DeleteMapping("/templates/{id}")
    public void remove(@PathVariable String id, @RequestParam long version) {
        service.deleteTemplate(id, version);
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    public ResponseEntity<?> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(RuntimeException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }
}
