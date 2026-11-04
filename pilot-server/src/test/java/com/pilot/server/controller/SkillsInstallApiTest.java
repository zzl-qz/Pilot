package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.pilot.core.session.SessionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 安装全链路 e2e：preview → install → GET 清单可见，冲突与覆盖、参数校验。 */
@SpringBootTest(properties = {"pilot.store=memory", "pilot.runtime.provision=false", "pilot.web-search.local=false"})
@AutoConfigureMockMvc
class SkillsInstallApiTest {
    private static final Path DATA =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-skills-install-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    SessionService sessions;
    @TempDir
    Path workspace;
    @TempDir
    Path source;

    @Test
    void previewInstallAndListRoundTripIntoProjectRoot() throws Exception {
        writeSkill("api-review", "/ireview");
        String session = sessions.createSession("安装测试", workspace.toString()).id();

        String token = previewToken("api-review", session);

        mvc.perform(post("/api/skills/install").contentType(MediaType.APPLICATION_JSON).content(
            json(Map.of("token", token, "skills", List.of("api-review"), "target", "project", "sessionId", session))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].name").value("api-review"))
            .andExpect(jsonPath("$.results[0].status").value("installed"));

        assertTrue(Files.isRegularFile(workspace.resolve(".pilot/skills/api-review/SKILL.md")));
        mvc.perform(get("/api/skills").param("sessionId", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.available[?(@.name=='api-review')]").exists());
    }

    @Test
    void conflictThenOverwriteReplacesDirectory() throws Exception {
        writeSkill("conflicted", "/iconflict");
        Path existing = Files.createDirectories(workspace.resolve(".pilot/skills/conflicted"));
        Files.writeString(existing.resolve("SKILL.md"), "旧内容");
        String session = sessions.createSession("冲突测试", workspace.toString()).id();

        String token = previewToken("conflicted", session);
        mvc.perform(post("/api/skills/install").contentType(MediaType.APPLICATION_JSON).content(
            json(Map.of("token", token, "skills", List.of("conflicted"), "target", "project", "sessionId", session))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("conflict"));
        assertTrue(Files.readString(existing.resolve("SKILL.md")).contains("旧内容"), "冲突时不改动旧目录");

        mvc.perform(post("/api/skills/install").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("token", token, "skills", List.of("conflicted"), "target", "project", "sessionId",
                session, "overwrite", true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("installed"));
        assertTrue(Files.readString(existing.resolve("SKILL.md")).contains("安装测试用正文"));
        assertFalse(Files.readString(existing.resolve("SKILL.md")).contains("旧内容"));
    }

    @Test
    void badTokensSourcesAndTargetsAreRejected() throws Exception {
        writeSkill("whatever", null);

        mvc.perform(post("/api/skills/preview").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("source", "github.com/u/repo")))).andExpect(status().isBadRequest());

        mvc.perform(post("/api/skills/preview").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("source", source.resolve("whatever").toString(), "sessionId", "missing"))))
            .andExpect(status().isNotFound());

        mvc.perform(post("/api/skills/install").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("token", "no-such-token", "skills", List.of("x"), "target", "user"))))
            .andExpect(status().isBadRequest());

        String token = previewToken("whatever", null);
        mvc.perform(post("/api/skills/install").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("token", token, "skills", List.of(), "target", "user"))))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/skills/install").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("token", token, "skills", List.of("whatever"), "target", "everywhere"))))
            .andExpect(status().isBadRequest());
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private void writeSkill(String name, String command) throws Exception {
        Path dir = Files.createDirectories(source.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: 安装测试用技能。"
            + (command == null ? "" : "\ncommand: " + command) + "\n---\n安装测试用正文");
    }

    private String previewToken(String skillName, String sessionId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("source", source.resolve(skillName).toString());
        if (sessionId != null) {
            body.put("sessionId", sessionId);
        }
        MvcResult result =
            mvc.perform(post("/api/skills/preview").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(payload.hasNonNull("token"));
        return payload.get("token").asText();
    }
}
