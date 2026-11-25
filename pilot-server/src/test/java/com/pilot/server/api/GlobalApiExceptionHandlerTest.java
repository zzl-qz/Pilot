package com.pilot.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.InvalidPathException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GlobalApiExceptionHandlerTest {
    private final GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler();

    @Test
    void responseStatusPreservesStatusAndChineseReasonWithoutStackTrace() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND}) {
            var response = handler.responseStatus(new ResponseStatusException(status, "请求资源不存在"));
            assertEquals(status.value(), response.getStatusCode().value());
            assertBody(response.getBody(), status, "请求资源不存在");
        }
    }

    @Test
    void invalidPathReturnsChineseBadRequestWithoutStackTrace() {
        var response = handler.invalidArgument(new InvalidPathException("../bad", "路径非法"));
        assertEquals(400, response.getStatusCode().value());
        assertBody(response.getBody(), HttpStatus.BAD_REQUEST, "路径非法");
    }

    private static void assertBody(Map<String, Object> body, HttpStatus status, String message) {
        assertEquals(status.value(), body.get("status"));
        assertEquals(status.getReasonPhrase(), body.get("error"));
        assertEquals(message, body.get("message"));
        assertTrue(String.valueOf(body.get("message")).matches(".*[\\p{IsHan}].*"));
        assertFalse(body.containsKey("stackTrace"));
    }
}
