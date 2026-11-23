package com.pilot.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunStarted;
import com.pilot.core.session.InMemoryRunCheckpointRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.run.RecoveryReportService;
import com.pilot.server.run.RunStateTracker;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.store.StorageHealthService;
import com.pilot.server.validation.ValidationLedger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DiagnosticsExportControllerTest {
    @TempDir
    Path root;

    private SessionService sessions;
    private RecoveryReportService recovery;
    private JsonMapper mapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sessions = new SessionService();
        recovery = new RecoveryReportService();
        mapper = JsonMapper.builder().build();
        StorageHealthService storageHealth = new StorageHealthService();
        RunStateTracker runStates =
            new RunStateTracker(new InMemoryRunCheckpointRepository(), "inst-test", storageHealth);
        ValidationLedger ledger = new ValidationLedger(new PilotDataDir(root.resolve("data").toString()), mapper);
        TaskDiagnosticsService diagnostics = new TaskDiagnosticsService(sessions, runStates, ledger, recovery,
            new DefaultListableBeanFactory().getBeanProvider(CommandTaskManager.class), storageHealth);
        DiagnosticsExportService exporter = new DiagnosticsExportService(sessions, recovery, runStates, mapper);
        DiagnosticsController controller =
            new DiagnosticsController(sessions, diagnostics, exporter, ledger, recovery, runStates);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exportsGlobalDiagnosticsBundleWhenSessionIdIsOmitted() throws Exception {
        recovery.record("inst-test", List.of(), "启动恢复完成");

        MvcResult result = mockMvc.perform(get("/api/diagnostics/export")).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", Matchers.containsString("application/json")))
            .andExpect(header().string("Content-Type", Matchers.containsString("charset=UTF-8")))
            .andExpect(header().string("Content-Disposition",
                Matchers.matchesPattern("attachment; filename=\"pilot-diagnostics-\\d{8}-\\d{6}\\.json\"")))
            .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(body.path("sanitized").asBoolean());
        assertTrue(body.path("sessionId").isNull());
        assertEquals("inst-test", body.path("instanceId").asString());
        assertFalse(body.path("exportedAt").asString().isBlank());
        assertFalse(body.path("appVersion").asString().isBlank());
        assertFalse(body.path("os").asString().isBlank());
        assertFalse(body.path("javaVersion").asString().isBlank());
        assertEquals(0, body.path("recentEvents").size());
        assertEquals(0, body.path("failedRequests").size());
        assertEquals("启动恢复完成", body.path("recovery").path("note").asString());
    }

    @Test
    void exportsSessionDiagnosticsWith200EventCapFailedRequestsAndStrictRedaction() throws Exception {
        String sessionId = sessions.createSession("脱敏导出测试", root.toString()).id();
        for (int i = 0; i < 220; i++) {
            sessions.publish(sessionId, "run_1", new RunStarted("普通步骤 " + i));
        }
        sessions.publish(sessionId, "run_1",
            new RunRetrying(1, 2000L, "上游 429，Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secretToken999"));
        sessions.publish(sessionId, "run_1",
            new RunFailed("MODEL_ERROR",
                "请求失败 key=sk-live-secret1234567890 password=superSecret999 https://admin:topSecret@internal.local/api",
                true));
        recovery.record("inst-test",
            List.of(new RunStateTracker.RecoveryEntry("run_old", sessionId, "step-2", "Bearer secretBearerToken12345",
                "-----BEGIN PRIVATE KEY-----\nabc123\n-----END PRIVATE KEY-----", root.toString(), "released",
                Instant.now())),
            "恢复备注 api_key=sk-test-abcdef123456");

        MvcResult result = mockMvc.perform(get("/api/diagnostics/export").param("sessionId", sessionId))
            .andExpect(status().isOk()).andReturn();

        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(raw.contains("sk-live-secret1234567890"), "导出内容不能泄露 sk- 密钥");
        assertFalse(raw.contains("sk-test-abcdef123456"), "导出内容不能泄露恢复备注里的 sk- 密钥");
        assertFalse(raw.contains("eyJhbGciOiJIUzI1NiJ9.secretToken999"), "导出内容不能泄露 Bearer 令牌");
        assertFalse(raw.contains("secretBearerToken12345"), "导出内容不能泄露恢复记录里的 Bearer 令牌");
        assertFalse(raw.contains("superSecret999"), "导出内容不能泄露密码字段");
        assertFalse(raw.contains("topSecret"), "导出内容不能泄露 URL 口令");
        assertFalse(raw.contains("BEGIN PRIVATE KEY"), "导出内容不能泄露私钥块");

        JsonNode body = mapper.readTree(raw);
        assertTrue(body.path("sanitized").asBoolean());
        assertEquals(sessionId, body.path("sessionId").asString());
        assertEquals(200, body.path("recentEvents").size(), "recentEvents 最多保留最近 200 条");
        assertEquals(222L, body.path("recentEvents").get(199).path("sequence").asLong());
        assertEquals(2, body.path("failedRequests").size());
        assertEquals("run.retrying", body.path("failedRequests").get(0).path("kind").asString());
        assertEquals("run.failed", body.path("failedRequests").get(1).path("kind").asString());
    }

    @Test
    void returnsNotFoundWhenSessionDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/diagnostics/export").param("sessionId", "ses_missing"))
            .andExpect(status().isNotFound());
    }
}
