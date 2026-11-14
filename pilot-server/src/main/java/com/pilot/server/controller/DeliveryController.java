package com.pilot.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.common.web.ApiErrors;
import com.pilot.server.notifications.DeliveryService;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {
    private final DeliveryService service;

    public DeliveryController(DeliveryService service) {
        this.service = service;
    }

    @GetMapping
    public Object list() {
        return service.list();
    }

    @GetMapping("/settings")
    public Object settings() {
        return service.settings();
    }

    public record Settings(long version, boolean desktop, String webhookUrl) {
    }

    @PutMapping("/settings")
    public Object settings(@RequestBody Settings input) {
        return service.configure(input.version(), input.desktop(), input.webhookUrl());
    }

    @PostMapping("/claim")
    public ResponseEntity<?> claim() {
        var d = service.claim("DESKTOP");
        return d == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(d);
    }

    @PostMapping("/{id}/receipt")
    public void receipt(@PathVariable String id, @RequestBody DeliveryService.Receipt input) {
        if (!"DESKTOP".equals(service.get(id).channel())) {
            throw new IllegalArgumentException("该投递由服务端处理");
        }
        service.receipt(id, input);
    }

    public record Retry(long version) {
    }

    @PostMapping("/{id}/retry")
    public void retry(@PathVariable String id, @RequestBody Retry input) {
        service.retry(id, input.version());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> bad(RuntimeException e) {
        return ApiErrors.badRequest(e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(RuntimeException e) {
        return ApiErrors.conflict(e);
    }
}
