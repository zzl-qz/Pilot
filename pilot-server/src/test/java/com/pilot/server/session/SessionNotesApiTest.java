package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.event.RunStarted;
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
    void noteSourceIsStoredAndMarkedAvailableOnlyWhileTheMessageExists() throws Exception {
        String session = sessions.createSession("想法来源").id();
        sessions.publish(session, "run_source", new RunStarted("来源消息"));
        String body = json.writeValueAsString(Map.of("content", "晚点补上并发测试", "source",
            Map.of("sequence", 1, "excerpt", "建议补一条并发测试", "role", "assistant")));
        mvc.perform(
            post("/api/sessions/{session}/notes", session).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source.sequence").value(1))
            .andExpect(jsonPath("$.source.excerpt").value("建议补一条并发测试"))
            .andExpect(jsonPath("$.source.role").value("assistant"))
            .andExpect(jsonPath("$.source.available").value(true));
        mvc.perform(get("/api/sessions/{session}/notes", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].source.available").value(true));

        String missing = json.writeValueAsString(
            Map.of("content", "来源已被清理", "source", Map.of("sequence", 999, "excerpt", "旧原文", "role", "user")));
        mvc.perform(
            post("/api/sessions/{session}/notes", session).contentType(MediaType.APPLICATION_JSON).content(missing))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source.available").value(false))
            .andExpect(jsonPath("$.source.excerpt").value("旧原文"));
    }

    @Test
    void projectNotesSearchOnlyReturnsCurrentProjectSessions() throws Exception {
        String p1 = sessions.createSession("项目一的一号会话", null, "project-a").id();
        String p1b = sessions.createSession("项目一的二号会话", null, "project-a").id();
        String p2 = sessions.createSession("项目二的会话", null, "project-b").id();
        for (String[] item : new String[][] {{p1, "登录失败路径要覆盖"}, {p1b, "登录成功路径也覆盖"}, {p2, "登录属于另一个项目"}}) {
            mvc.perform(post("/api/sessions/{session}/notes", item[0]).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", item[1])))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/library/notes/search").param("q", "登录").param("projectId", "project-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].sessionTitle").exists())
            .andExpect(jsonPath("$.items[0].snippet").value(Matchers.containsString("登录")));
        mvc.perform(get("/api/library/notes/search").param("q", "登录").param("projectId", "project-b"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].sessionId").value(p2));
        mvc.perform(get("/api/library/notes/search").param("q", "不存在的词").param("projectId", "project-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/library/notes/search").param("q", "登录")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
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
