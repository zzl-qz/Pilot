package com.pilot.server.agent;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.session.SessionService;
import com.pilot.server.WindowsJdkLoopbackSensitive;

@SpringBootTest(properties = {"pilot.store=memory", "pilot.runtime.provision=false", "pilot.web-search.local=false"})
@AutoConfigureMockMvc
@WindowsJdkLoopbackSensitive
class AgentProfilesApiTest {
    @TempDir
    static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", data::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionService sessions;
    @TempDir
    Path workspace;

    @Test
    void listsSourceContentInvalidAndAllowsUserOverrideCrudOnlyInUserRoot() throws Exception {
        Path projectAgents = Files.createDirectories(workspace.resolve(".pilot").resolve("agents"));
        Files.writeString(projectAgents.resolve("proj-only.md"),
            "---\ndescription: 项目级只读角色\ntools: [read_file]\nwritable: false\n---\n项目级提示词。\n");
        Files.writeString(projectAgents.resolve("broken.md"), "缺少 frontmatter");
        String session = sessions.createSession("代理测试", workspace.toString()).id();
        String reviewer = "---\ndescription: 用户审查\ntools: [read_file]\nwritable: false\n---\n只审查。";

        mvc.perform(get("/api/agents").param("sessionId", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.available[?(@.name == 'explorer')].source").value("builtin"))
            .andExpect(
                jsonPath("$.available[?(@.name == 'explorer')].content", hasItem(containsString("description:"))))
            .andExpect(jsonPath("$.available[?(@.name == 'proj-only')].source").value("project"))
            .andExpect(jsonPath("$.available[?(@.name == 'proj-only')].content", hasItem(containsString("项目级提示词"))))
            .andExpect(jsonPath("$.invalid[?(@.name == 'broken')].reason", hasItem(containsString("frontmatter"))));

        mvc.perform(put("/api/agents/reviewer").contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":" + quote(reviewer) + "}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("user")).andExpect(jsonPath("$.writable").value(false))
            .andExpect(jsonPath("$.hasUserOverride").value(true));

        mvc.perform(get("/api/agents").param("sessionId", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.available[?(@.name == 'reviewer')].source").value("user"))
            .andExpect(jsonPath("$.available[?(@.name == 'reviewer')].description").value("用户审查"))
            .andExpect(jsonPath("$.available[?(@.name == 'reviewer')].hasUserOverride").value(true));

        mvc.perform(delete("/api/agents/reviewer")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/agents/reviewer")).andExpect(status().isNotFound());
        mvc.perform(get("/api/agents").param("sessionId", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.available[?(@.name == 'reviewer')].source").value("builtin"));
        assertFalse(Files.exists(projectAgents.resolve("reviewer.md")));
    }

    @Test
    void rejectsTraversalInvalidDefinitionsTooLongPromptsAndMissingSession() throws Exception {
        mvc.perform(
            put("/api/agents/invalid_name").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"bad\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/agents/valid").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"bad\"}"))
            .andExpect(status().isBadRequest());
        String mismatchedName = "---\nname: other\ndescription: 名称不一致\n---\n提示词。";
        mvc.perform(put("/api/agents/valid").contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":" + quote(mismatchedName) + "}")).andExpect(status().isBadRequest());
        String tooLongBody = "---\ndescription: 超长提示词\n---\n" + "line\n".repeat(305);
        mvc.perform(put("/api/agents/valid").contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":" + quote(tooLongBody) + "}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agents").param("sessionId", "missing")).andExpect(status().isNotFound());
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
