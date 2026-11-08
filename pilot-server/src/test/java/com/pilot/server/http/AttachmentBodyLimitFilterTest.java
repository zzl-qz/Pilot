package com.pilot.server.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.pilot.server.config.AttachmentLimits;

class AttachmentBodyLimitFilterTest {
    private final AttachmentLimits limits = new AttachmentLimits(64, 2 * 1024 * 1024, 25 * 1024 * 1024);

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

    private static MockHttpServletRequest post(String uri, int contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(uri);
        request.setContent(new byte[contentLength]);
        return request;
    }
}
