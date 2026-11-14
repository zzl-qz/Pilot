package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pilot.server.common.data.PilotDataDir;

import tools.jackson.databind.ObjectMapper;

class MobileReceiptControllerTest {
    @TempDir
    Path directory;

    @Test
    void deviceIdentityIsRequiredAndMaintenanceReportsWithoutCreatingBusinessReceipts() throws Exception {
        var mapper = new ObjectMapper();
        var receipts = new MobileReceiptStore(new PilotDataDir(directory.toString()), mapper, 24, 30, 10, 536870912);
        var mvc = MockMvcBuilders.standaloneSetup(new MobileReceiptController(receipts)).build();
        mvc.perform(get("/api/mobile/receipts/token")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/mobile/receipts/token").header("X-Pilot-Device-Id", "bad"))
            .andExpect(status().isBadRequest());
        var result = mvc.perform(get("/api/mobile/receipts/token").header("X-Pilot-Device-Id", "a".repeat(64)))
            .andExpect(status().isOk()).andReturn();
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        String id = mapper.readTree(result.getResponse().getContentAsString()).get("requestId").asString();
        mvc.perform(post("/api/mobile/receipts/ack").header("X-Pilot-Device-Id", "a".repeat(64))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new MobileReceiptController.Acknowledgment(id))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/maintenance/mobile-receipts")).andExpect(status().isOk());
        mvc.perform(post("/api/maintenance/mobile-receipts/cleanup")).andExpect(status().isOk());
        assertEquals(0, receipts.status().count());
    }
}
