package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.server.mobile.MobileConnectionService;

import tools.jackson.databind.ObjectMapper;

class MobileConnectionControllerTest {
    @Test
    void serviceFailureReturnsExplicitReasonIndependentOfSpringErrorSettings() {
        MobileConnectionService service = mock(MobileConnectionService.class);
        String reason = "Windows x64 原生连接库加载失败，请检查 Microsoft Visual C++ x64 运行库";
        when(service.request(eq("pair"), any())).thenThrow(new IllegalStateException(reason));
        var body = new ObjectMapper().createObjectNode().put("reuse", true);
        var result = new MobileConnectionController(service).pair(local(), body);
        assertEquals(503, result.getStatusCode().value());
        assertEquals("no-store", result.getHeaders().getFirst("Cache-Control"));
        assertEquals(reason, result.getBody().path("message").asText());
        assertEquals(reason, result.getBody().path("error").asText());
        assertEquals("MOBILE_CONNECTION_UNAVAILABLE", result.getBody().path("code").asText());
    }

    @Test
    void localSameOriginWorksWithoutExposingAdministrativeToken() {
        MobileConnectionService service = mock(MobileConnectionService.class);
        var payload = new ObjectMapper().readTree("{\"running\":true}");
        when(service.request("status", null)).thenReturn(payload);
        MockHttpServletRequest request = local();
        request.addHeader("Origin", "http://localhost");
        var result = new MobileConnectionController(service).status(request);
        assertEquals("no-store", result.getHeaders().getFirst("Cache-Control"));
        assertEquals(payload, result.getBody());
    }

    @Test
    void rejectsRemoteCrossSiteAndRebindingBeforeStartingBridge() {
        MobileConnectionService service = mock(MobileConnectionService.class);
        MobileConnectionController controller = new MobileConnectionController(service);
        MockHttpServletRequest remote = local();
        remote.setRemoteAddr("192.168.1.3");
        MockHttpServletRequest cross = local();
        cross.addHeader("Origin", "https://evil.example");
        MockHttpServletRequest rebinding = local();
        rebinding.setServerName("evil.example");
        MockHttpServletRequest fetch = local();
        fetch.addHeader("Sec-Fetch-Site", "cross-site");
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {remote, cross, rebinding, fetch}) {
            assertEquals(403,
                assertThrows(ResponseStatusException.class, () -> controller.status(request)).getStatusCode().value());
            assertEquals(403,
                assertThrows(ResponseStatusException.class, () -> controller.repair(request)).getStatusCode().value());
        }
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequest local() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mobile/connection");
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        request.setServerPort(80);
        return request;
    }
}
