package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.pilot.server.config.PilotDataDir;

import tools.jackson.databind.ObjectMapper;

class MobileRequestFilterTest {
    @TempDir
    Path directory;
    private String requestId;

    @BeforeEach
    void issueRequest() throws Exception {
        requestId = "a".repeat(64) + ":" + store().issue("a".repeat(64));
    }

    private MobileReceiptStore store() {
        return new MobileReceiptStore(new PilotDataDir(directory.toString()), new ObjectMapper(), 24, 30, 10000,
            536870912);
    }

    private MobileRequestFilter filter() {
        return new MobileRequestFilter(store());
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/sessions/ses_1/messages");
        request.addHeader("X-Pilot-Request-Id", requestId);
        request.addHeader("X-Pilot-Device-Id", "a".repeat(64));
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void replaysAfterRestartWithoutExecutingTwiceAndPreservesBody() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (request, response) -> {
            calls.incrementAndGet();
            assertEquals("中文指令", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"accepted\":true}");
        };
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter().doFilter(request("中文指令"), first, chain);
        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter().doFilter(request("中文指令"), retry, chain);
        assertEquals(1, calls.get());
        assertEquals(first.getContentAsString(), retry.getContentAsString());
        assertEquals("true", retry.getHeader("X-Pilot-Replayed"));
    }

    @Test
    void rejectsReuseForDifferentInputAndUnknownCrashOutcome() throws Exception {
        MobileRequestFilter filter = filter();
        assertThrows(IllegalStateException.class,
            () -> filter.doFilter(request("原指令"), new MockHttpServletResponse(), (request, response) -> {
                throw new IllegalStateException("模拟执行后断电");
            }));
        MockHttpServletResponse unknown = new MockHttpServletResponse();
        filter().doFilter(request("原指令"), unknown, (request, response) -> fail("不能重做结果未知的操作"));
        assertEquals(409, unknown.getStatus());
        assertTrue(unknown.getContentAsString().contains("\"uncertain\":true"));
        MockHttpServletResponse changed = new MockHttpServletResponse();
        filter().doFilter(request("另一条指令"), changed, (request, response) -> fail("不能复用标识"));
        assertEquals(409, changed.getStatus());
    }

    @Test
    void rejectsMalformedKeysAndOversizeBodyBeforeExecuting() throws Exception {
        MockHttpServletRequest invalid = request("{}");
        invalid.removeHeader("X-Pilot-Request-Id");
        invalid.addHeader("X-Pilot-Request-Id", "../../secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(invalid, response, (request, result) -> fail("非法标识不能执行"));
        assertEquals(400, response.getStatus());
        response = new MockHttpServletResponse();
        filter().doFilter(request("x".repeat(12 * 1024 * 1024 + 1)), response, (request, result) -> fail("超限不能执行"));
        assertEquals(413, response.getStatus());
    }

    @Test
    void webMessagesUseAttachmentBudgetInsteadOfMobileReceiptCap() throws Exception {
        MockHttpServletRequest request = request("x".repeat(13 * 1024 * 1024));
        request.removeHeader("X-Pilot-Device-Id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, (input, result) -> {
            assertEquals(13 * 1024 * 1024, input.getInputStream().readAllBytes().length);
            result.getWriter().write("{}");
        });
        assertEquals(200, response.getStatus());
    }
}
