package com.pilot.server.controller;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunStarted;
import com.pilot.core.session.Run;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.RunStarter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class SessionApiTest {
    private static final Path ATTACHMENT_ROOT = Path.of(System.getProperty("java.io.tmpdir"),
            "pilot-session-api-" + java.util.UUID.randomUUID());

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        TestRunStarter testRunStarter() {
            return new TestRunStarter();
        }
    }

    static class TestRunStarter implements RunStarter {
        private final AtomicBoolean running = new AtomicBoolean();
        private final List<String> queue = new ArrayList<>();

        void setRunning(boolean value) {
            running.set(value);
            queue.clear();
        }

        @Override
        public void start(String sessionId, String runId, String input) {
        }

        @Override
        public Optional<String> activeRun(String sessionId) {
            return running.get() ? Optional.of("run_active") : Optional.empty();
        }

        @Override
        public boolean steer(String sessionId, String text) {
            if (!running.get() || text == null || text.isBlank()) {
                return false;
            }
            queue.add(text.strip());
            return true;
        }

        @Override
        public List<String> queued(String sessionId) {
            return List.copyOf(queue);
        }

        @Override
        public boolean editQueued(String sessionId, int index, String text) {
            if (text == null || text.isBlank() || index < 0 || index >= queue.size()) {
                return false;
            }
            queue.set(index, text.strip());
            return true;
        }

        @Override
        public boolean removeQueued(String sessionId, int index) {
            if (index < 0 || index >= queue.size()) {
                return false;
            }
            queue.remove(index);
            return true;
        }
    }
    @DynamicPropertySource
    static void attachmentProperties(DynamicPropertyRegistry registry) {
        registry.add("pilot.attachments.dir", () -> ATTACHMENT_ROOT.toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionService sessions;

    @Autowired
    private TestRunStarter runStarter;

    @TempDir
    Path workspace;

    @Test
    void createSessionAndSendMessageReturnIds() throws Exception {
        String sessionId = createSession();

        mvc.perform(post("/api/sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"修复边界条件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.runId").isNotEmpty());

        var events = sessions.events(sessionId);
        assertEquals(1, events.size());
        assertEquals("run.started", events.get(0).type());
        assertEquals("修复边界条件", ((RunStarted) events.get(0).payload()).input());
    }

    /** 刷新页面后前端靠这个接口恢复历史会话，不能只返回当前进程内新建的会话。 */
    @Test
    void listsSessionsForHistoryRestore() throws Exception {
        String sessionId = createSession();

        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").isNotEmpty())
                .andExpect(jsonPath("$[0].status").isNotEmpty());

        String body = mvc.perform(get("/api/sessions"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains(sessionId), body);
    }

    @Test
    void rejectsAttachmentsDuringActiveRunWithoutPersistingThemAndQueuesText() throws Exception {
        String sessionId = createSession();
        Path attachmentRoot = ATTACHMENT_ROOT.resolve(sessionId);
        runStarter.running.set(true);
        try {
            String attachment = json.writeValueAsString(Map.of(
                    "content", "检查这个文件",
                    "attachments", List.of(Map.of(
                            "name", "payload.bin",
                            "mimeType", "application/octet-stream",
                            "dataBase64", "AQIDBA=="))));
            mvc.perform(post("/api/sessions/{id}/messages", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(attachment))
                    .andExpect(status().isConflict());

            assertFalse(Files.exists(attachmentRoot), "冲突响应前不应创建附件目录或文件");

            mvc.perform(post("/api/sessions/{id}/messages", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"补充说明\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.sessionId").value(sessionId))
                    .andExpect(jsonPath("$.runId").value("run_active"));
        } finally {
            runStarter.running.set(false);
        }
    }

    /** 首屏只回放尾部一页，向上翻页用 before 游标。 */
    @Test
    void pagesHistoryFromTheTail() throws Exception {
        String sessionId = createSession();
        for (int i = 0; i < 5; i++) {
            sessions.publish(sessionId, "run_hist", new AssistantDelta("片段" + i));
        }

        mvc.perform(get("/api/sessions/{id}/history", sessionId).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[1].sequence").value(5));

        mvc.perform(get("/api/sessions/{id}/history", sessionId)
                        .param("limit", "2")
                        .param("before", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].sequence").value(2))
                .andExpect(jsonPath("$.events[1].sequence").value(3))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    /** 权限档位要能在运行时切换，改完立刻反映到 health，不需要重启。 */
    @Test
    void switchesPermissionModeAtRuntime() throws Exception {
        try {
            mvc.perform(put("/api/settings/permission-mode")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"AUTO_EDIT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissionMode").value("AUTO_EDIT"));

            mvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissionMode").value("AUTO_EDIT"));

            mvc.perform(get("/api/settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissionMode").value("AUTO_EDIT"))
                    .andExpect(jsonPath("$.permissionModes").isArray());
        } finally {
            mvc.perform(put("/api/settings/permission-mode")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"READ_ONLY\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rejectsUnknownPermissionMode() throws Exception {
        mvc.perform(put("/api/settings/permission-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ROOT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsSessionWithSelectedWorkspace() throws Exception {
        String body = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "选目录", "workspace", workspace.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String sessionId = json.readTree(body).get("sessionId").asText();
        assertEquals(workspace.toRealPath().toString(), json.readTree(body).get("workspace").asText());
        assertEquals(workspace.toRealPath().toString(), sessions.find(sessionId).orElseThrow().workspace());
    }

    @Test
    void rejectsMissingWorkspaceDirectory() throws Exception {
        String missing = workspace.resolve("does-not-exist").toString();
        mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "坏目录", "workspace", missing))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browsesLocalDirectories() throws Exception {
        Files.createDirectory(workspace.resolve("child-project"));

        mvc.perform(get("/api/workspaces/browse").param("path", workspace.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directories[0].name").value("child-project"))
                .andExpect(jsonPath("$.gitRepository").value(false));
    }

    @Test
    void rejectsRollbackWithoutSnapshot() throws Exception {
        mvc.perform(post("/api/runs/{runId}/rollback", "run_missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMessageForUnknownSession() throws Exception {
        mvc.perform(post("/api/sessions/{id}/messages", "ses_missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamsStoredEventsAsSse() throws Exception {
        String sessionId = createSession();
        Run run = sessions.sendMessage(sessionId, "你好");
        sessions.publish(sessionId, run.runId(), new AssistantDelta("世界"));

        MvcResult result = mvc.perform(get("/api/sessions/{id}/events", sessionId))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:run.started"), body);
        assertTrue(body.contains("event:assistant.delta"), body);
        assertTrue(body.contains("id:1"), body);
        assertTrue(body.contains("\"sequence\":2"), body);
    }

    @Test
    void replaysOnlyEventsAfterCursor() throws Exception {
        String sessionId = createSession();
        Run run = sessions.sendMessage(sessionId, "你好");
        sessions.publish(sessionId, run.runId(), new AssistantDelta("世界"));

        MvcResult result = mvc.perform(get("/api/sessions/{id}/events", sessionId).param("after", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("event:run.started"), body);
        assertTrue(body.contains("event:assistant.delta"), body);
    }

    /** 计划清单刷新后要能恢复，空计划返回空数组而不是 404。 */
    @Test
    void returnsEmptyPlanForFreshSession() throws Exception {
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/plan", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").isArray())
                .andExpect(jsonPath("$.plan.length()").value(0));
    }

    /** 改已经发出去的一句：这句和后面的事件都删掉，再用新内容开一轮。 */
    @Test
    void resendsFromEditedUserMessage() throws Exception {
        runStarter.setRunning(false);
        String sessionId = createSession();
        mvc.perform(post("/api/sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"第一句\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"第二句\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/sessions/{id}/resend", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sequence\":1,\"content\":\"改过的第一句\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty());

        var events = sessions.events(sessionId);
        assertEquals(1, events.size());
        assertEquals("改过的第一句", ((RunStarted) events.get(0).payload()).input());
    }

    @Test
    void rejectsResendWhileRunning() throws Exception {
        String sessionId = createSession();
        mvc.perform(post("/api/sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"第一句\"}"))
                .andExpect(status().isOk());
        runStarter.setRunning(true);
        try {
            mvc.perform(post("/api/sessions/{id}/resend", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sequence\":1,\"content\":\"先别改\"}"))
                    .andExpect(status().isConflict());
            assertEquals(1, sessions.events(sessionId).size());
        } finally {
            runStarter.setRunning(false);
        }
    }

    /** 运行中再发的话进队列，发出去之前可以改、可以删。 */
    @Test
    void queuesFollowUpUntilItIsEditedOrRemoved() throws Exception {
        String sessionId = createSession();
        runStarter.setRunning(true);
        try {
        mvc.perform(post("/api/sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"等会儿再做这个\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run_active"));

        mvc.perform(get("/api/sessions/{id}/queue", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("等会儿再做这个"));

        mvc.perform(put("/api/sessions/{id}/queue/{index}", sessionId, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"改成先列计划\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("改成先列计划"));

        mvc.perform(delete("/api/sessions/{id}/queue/{index}", sessionId, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        } finally {
            runStarter.setRunning(false);
        }
    }

    /** 没有对话记忆时不能续跑，要明确返回冲突而不是静默开一个空 run。 */
    @Test
    void rejectsContinueWithoutConversation() throws Exception {
        String sessionId = createSession();

        mvc.perform(post("/api/sessions/{id}/continue", sessionId))
                .andExpect(status().isConflict());
    }

    private String createSession() throws Exception {
        String body = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"测试会话\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("sessionId").asText();
    }
}
