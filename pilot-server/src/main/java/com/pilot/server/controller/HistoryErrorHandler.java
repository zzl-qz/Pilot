package com.pilot.server.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.pilot.core.session.SessionService;

@RestControllerAdvice
public class HistoryErrorHandler {
    @ExceptionHandler(SessionService.HistoryChangedException.class)
    public ResponseEntity<Map<String, String>> changed(SessionService.HistoryChangedException failure) {
        return ResponseEntity.status(409).body(Map.of("message", failure.getMessage()));
    }
}
