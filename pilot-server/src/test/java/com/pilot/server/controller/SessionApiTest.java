package com.pilot.server.controller;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunStarted;
import com.pilot.core.session.Run;
import com.pilot.core.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "pilot.mock.enabled=false")
@AutoConfigureMockMvc
class SessionApiTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionService sessions;

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
