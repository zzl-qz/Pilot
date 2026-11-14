package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.controller.SubmissionController;

import tools.jackson.databind.ObjectMapper;

class SubmissionControllerTest {
    @TempDir
    Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private MobileReceiptStore store(String time) {
        return new MobileReceiptStore(new PilotDataDir(directory.toString()), mapper,
            Clock.fixed(Instant.parse(time), ZoneOffset.UTC), Duration.ofHours(24), Duration.ofDays(30), 100,
            536870912);
    }

    @Test
    void restartAndExpiryDoNotPreventReadOnlyVerificationOfEveryOutcome() throws Exception {
        MobileReceiptStore original = store("2026-01-01T00:00:00Z");
        String peer = "b".repeat(64);
        String[] states = {"unknown", "accepted", "rejected", "unknown", "unknown"};
        int[] statuses = {0, 202, 400, 500, 409};
        for (int i = 0; i < statuses.length; i++) {
            String id = peer + ":" + original.issue(peer);
            String body = i == 4 ? "{\"uncertain\":true}" : "{\"sessionId\":\"s\"}";
            original.save(id,
                new MobileReceiptStore.Receipt("fingerprint", statuses[i], "application/json",
                    Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)), original.now(),
                    original.now() + 86400000, 0));
            MobileReceiptStore reopened = store("2026-02-01T00:00:00Z");
            var mvc = MockMvcBuilders.standaloneSetup(new SubmissionController(reopened, mapper)).build();
            mvc.perform(get("/api/submissions/result").header("X-Pilot-Request-Id", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(states[i]));
            assertEquals(i + 1, reopened.status().count(), "查询不能新增业务回执");
        }
    }

    @Test
    void missingReceiptAndInvalidSignatureCannotBeTreatedAsUnexecuted() throws Exception {
        MobileReceiptStore receipts = store("2026-01-01T00:00:00Z");
        String peer = "a".repeat(64);
        String id = peer + ":" + receipts.issue(peer);
        var mvc = MockMvcBuilders.standaloneSetup(new SubmissionController(receipts, mapper)).build();
        mvc.perform(get("/api/submissions/result").header("X-Pilot-Request-Id", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("unknown"));
        mvc.perform(get("/api/submissions/result").header("X-Pilot-Request-Id", "c".repeat(64) + id.substring(64)))
            .andExpect(status().isBadRequest());
        assertThrows(MobileReceiptStore.Rejected.class, () -> receipts.lookup("../../secret"));
        assertEquals(0, receipts.status().count());
    }
}
