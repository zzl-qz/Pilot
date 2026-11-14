package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.session.SessionService;
import com.pilot.server.run.RunStarter;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"pilot.store=memory", "pilot.data.dir=${java.io.tmpdir}/pilot-notes-api-${random.uuid}"})
@AutoConfigureMockMvc
class SessionNotesApiTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SessionService sessions;
    @Autowired
    private RunStarter runStarter;

    @Test
    void notesCanBeCreatedUpdatedAndDeletedWithoutStartingRuns() throws Exception {
        String session = sessions.createSession("想法接口测试").id();
        String created = mvc
            .perform(post("/api/sessions/{session}/notes", session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"晚点补上并发测试\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("晚点补上并发测试")).andReturn().getResponse()
            .getContentAsString();
        String noteId = json.readTree(created).get("id").asString();
        assertTrue(sessions.events(session).isEmpty(), "保存想法不能产生会话事件");
        assertTrue(runStarter.queuedMessages(session).isEmpty(), "保存想法不能加入执行队列");

        mvc.perform(get("/api/sessions/{session}/notes", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(noteId)).andExpect(jsonPath("$[0].content").value("晚点补上并发测试"));

        mvc.perform(put("/api/sessions/{session}/notes/{note}", session, noteId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"已经补上并发测试\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("已经补上并发测试"));

        mvc.perform(delete("/api/sessions/{session}/notes/{note}", session, noteId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/{session}/notes", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void notesAreScopedToTheirSessionAndInputIsValidated() throws Exception {
        String first = sessions.createSession("想法会话一").id();
        String second = sessions.createSession("想法会话二").id();
        mvc.perform(post("/api/sessions/{session}/notes", first).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"只属于会话一\"}")).andExpect(status().isOk());

        mvc.perform(get("/api/sessions/{session}/notes", second)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/sessions/{session}/notes", "missing-session")).andExpect(status().isNotFound());
        mvc.perform(post("/api/sessions/{session}/notes", first).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/sessions/{session}/notes/{note}", first, "note_missing")
            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"改不到\"}"))
            .andExpect(status().isBadRequest());
        // 删除不存在的想法是幂等的，多端重复删除不互相打断
        mvc.perform(delete("/api/sessions/{session}/notes/{note}", first, "note_missing"))
            .andExpect(status().isNoContent());
    }

    @Test
    void deletingSessionClearsItsNotesWithTheSession() throws Exception {
        String session = sessions.createSession("想法级联删除").id();
        mvc.perform(post("/api/sessions/{session}/notes", session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"删除会话时一起清掉\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/sessions/{id}", session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/{session}/notes", session)).andExpect(status().isNotFound());
    }
}
