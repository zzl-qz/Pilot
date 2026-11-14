package com.pilot.server.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiErrorsTest {
    @Test
    void buildsMessageBodyWithMatchingStatus() {
        var invalid = ApiErrors.badRequest(new IllegalArgumentException("参数不对"));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals(Map.of("message", "参数不对"), invalid.getBody());

        var conflict = ApiErrors.conflict(new IllegalStateException("已变化"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(Map.of("message", "已变化"), conflict.getBody());

        var custom = ApiErrors.message(HttpStatus.PAYLOAD_TOO_LARGE, "太大");
        assertEquals(413, custom.getStatusCode().value());
        assertEquals(Map.of("message", "太大"), custom.getBody());
    }

    @Test
    void fileEndpointsKeepLegacyErrorField() {
        var failure = ApiErrors.error(HttpStatus.BAD_REQUEST, "非法路径");
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals(Map.of("error", "非法路径"), failure.getBody());
    }

    @Test
    void missingSessionKeepsHistoricalNotFoundText() {
        var missing = SessionLookup.notFound("s1");
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("session not found: s1", missing.getReason());
    }

    /** 没有提示语时仍返回调用方声明的状态码，用兜底文案把响应体补上。 */
    @Test
    void missingMessageKeepsDeclaredStatus() {
        var invalid = ApiErrors.badRequest(new IllegalArgumentException());
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals(Map.of("message", ApiErrors.FALLBACK), invalid.getBody());

        var file = ApiErrors.error(HttpStatus.BAD_REQUEST, null);
        assertEquals(Map.of("error", ApiErrors.FALLBACK), file.getBody());
    }

    @Test
    void chineseSessionTextsStayAsPublished() {
        assertEquals("会话不存在: s1", SessionLookup.missing("s1").getReason());
        assertEquals("会话不存在", SessionLookup.missing().getReason());
    }
}
