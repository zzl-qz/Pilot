package com.pilot.server.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pilot.server.config.AttachmentLimits;

class AttachmentBodyLimitFilterTest {
    private final AttachmentLimits limits = new AttachmentLimits(64, 2 * 1024 * 1024, 25 * 1024 * 1024, 20_000);

    @Test
    void rejectsOversizedMessageBodyBeforeReading() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AttachmentBodyLimitFilter(limits).doFilter(post("/api/sessions/ses_1/messages", 50_000_000), response,
            (req, res) -> {
            });

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("总量预算"), response.getContentAsString());
    }

    @Test
    void passesNormalSizedMessageThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        int[] calls = {0};

        new AttachmentBodyLimitFilter(limits).doFilter(post("/api/sessions/ses_1/messages", 1_000), response,
            (req, res) -> calls[0]++);

        assertNotEquals(413, response.getStatus());
        assertEquals(1, calls[0]);
    }

    @Test
    void ignoresNonAttachmentEndpoints() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/sessions/ses_1/steer");
        request.addHeader("Content-Length", "50000000");
        MockHttpServletResponse response = new MockHttpServletResponse();
        int[] calls = {0};

        new AttachmentBodyLimitFilter(limits).doFilter(request, response, (req, res) -> calls[0]++);

        assertEquals(1, calls[0]);
    }

    @Test
    void limitsUnknownLengthAndDishonestLengthsDuringBulkReads() throws Exception {
        AttachmentLimits small = new AttachmentLimits(1, 3, 3, 100);
        int budget = (int)small.maxRequestBytes();
        for (long reported : new long[] {-1, 1}) {
            MockHttpServletRequest request = new MockHttpServletRequest() {
                @Override
                public long getContentLengthLong() {
                    return reported;
                }
            };
            request.setMethod("POST");
            request.setRequestURI("/api/sessions/s/messages");
            request.setContent(new byte[budget + 10]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean[] consumed = {false};
            new AttachmentBodyLimitFilter(small).doFilter(request, response, (req, res) -> {
                req.getInputStream().readAllBytes();
                consumed[0] = true;
            });
            assertEquals(413, response.getStatus());
            assertTrue(!consumed[0], "超限读取不能返回完整正文");
            assertEquals(9, request.getInputStream().available(), "只允许多读一个字节确认越界");
        }
    }

    @Test
    void acceptsExactBudgetWithReaderAndContextPath() throws Exception {
        AttachmentLimits small = new AttachmentLimits(1, 3, 3, 100);
        byte[] bytes = "a".repeat((int)small.maxRequestBytes()).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = post("/pilot/api/sessions/s/messages", bytes.length);
        request.setContextPath("/pilot");
        request.setContent(bytes);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AttachmentBodyLimitFilter(small).doFilter(request, response, (req, res) -> {
            assertEquals(bytes.length, req.getReader().readLine().length());
        });
        assertEquals(200, response.getStatus());
    }

    @Test
    void jacksonReturns413ForChunkedBodyWithoutCallingController() throws Exception {
        AttachmentLimits small = new AttachmentLimits(1, 3, 3, 100);
        TestEndpoint endpoint = new TestEndpoint();
        var mvc = MockMvcBuilders.standaloneSetup(endpoint).addFilters(new AttachmentBodyLimitFilter(small)).build();
        byte[] body =
            ("{\"content\":\"" + "x".repeat((int)small.maxRequestBytes()) + "\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(MockMvcRequestBuilders.post("/api/sessions/s/messages").with(ignored -> {
            MockHttpServletRequest request = new MockHttpServletRequest() {
                @Override
                public long getContentLengthLong() {
                    return -1;
                }

                @Override
                public int getContentLength() {
                    return -1;
                }
            };
            request.setMethod("POST");
            request.setRequestURI("/api/sessions/s/messages");
            request.setContentType("application/json");
            request.addHeader("Transfer-Encoding", "chunked");
            request.setContent(body);
            return request;
        })).andExpect(MockMvcResultMatchers.status().isPayloadTooLarge());
        assertEquals(0, endpoint.calls);
    }

    @org.springframework.web.bind.annotation.RestController
    static class TestEndpoint {
        int calls;

        @org.springframework.web.bind.annotation.PostMapping("/api/sessions/{id}/messages")
        public Map<String, Object>
            message(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
            calls++;
            return body;
        }
    }

    private static MockHttpServletRequest post(String uri, int contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(uri);
        request.setContent(new byte[contentLength]);
        return request;
    }
}
