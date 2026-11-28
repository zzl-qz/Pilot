package com.pilot.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.sql.SQLException;
import java.util.Map;
import java.util.NoSuchElementException;

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

    @Test
    void databaseIllegalStateAndOptionalNoSuchElementReturnInternalServerError() {
        var dbResponse = handler.conflict(new IllegalStateException("sqlite failed", new SQLException("SQLITE_BUSY")));
        assertEquals(500, dbResponse.getStatusCode().value());
        assertBody(dbResponse.getBody(), HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");

        var optResponse = handler.missingElement(new NoSuchElementException("No value present"));
        assertEquals(500, optResponse.getStatusCode().value());
        assertBody(optResponse.getBody(), HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");

        var domainConflict = handler.conflict(new IllegalStateException("会话当前正在运行"));
        assertEquals(409, domainConflict.getStatusCode().value());
        assertBody(domainConflict.getBody(), HttpStatus.CONFLICT, "会话当前正在运行");
    }

    @Test
    void uncheckedIoHidesRawSingleArgCauseDetailsButKeepsUserMessage() {
        var rawIo = handler.ioUnavailable(new UncheckedIOException(new IOException("/etc/secret/path denied")));
        assertEquals(503, rawIo.getStatusCode().value());
        assertBody(rawIo.getBody(), HttpStatus.SERVICE_UNAVAILABLE, "存储或文件操作失败，请稍后重试");
        assertFalse(String.valueOf(rawIo.getBody().get("message")).contains("/etc/secret"));

        var domainIo = handler.ioUnavailable(new UncheckedIOException("多 Agent 状态持久化失败", new IOException("disk full")));
        assertEquals(503, domainIo.getStatusCode().value());
        assertBody(domainIo.getBody(), HttpStatus.SERVICE_UNAVAILABLE, "多 Agent 状态持久化失败");
    }

    private static void assertBody(Map<String, Object> body, HttpStatus status, String message) {
        assertEquals(status.value(), body.get("status"));
        assertEquals(status.getReasonPhrase(), body.get("error"));
        assertEquals(message, body.get("message"));
        assertTrue(String.valueOf(body.get("message")).matches(".*[\\p{IsHan}].*"));
        assertFalse(body.containsKey("stackTrace"));
    }
}
