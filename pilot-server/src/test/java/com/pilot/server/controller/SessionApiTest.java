package com.pilot.server.controller;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunStarted;
import com.pilot.core.session.Run;
import com.pilot.core.session.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"pilot.mock.enabled=false", "pilot.agent.mode=mock", "pilot.store=memory"})
@AutoConfigureMockMvc
class SessionApiTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionService sessions;

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
