package com.pilot.server.session;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.pilot.core.session.EventTooLargeException;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.ApiErrors;

@RestControllerAdvice
public class HistoryErrorHandler {
    @ExceptionHandler(EventTooLargeException.class)
    public ResponseEntity<Map<String, String>> tooLarge(EventTooLargeException failure) {
        return ApiErrors.message(HttpStatus.PAYLOAD_TOO_LARGE, failure.getMessage());
    }

    @ExceptionHandler(SessionService.HistoryChangedException.class)
    public ResponseEntity<Map<String, String>> changed(SessionService.HistoryChangedException failure) {
        return ApiErrors.conflict(failure);
    }
}
