package com.pilot.server.skill;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.session.SessionService;

/**
 * 技能查询 API：按会话工作区解析目录；roots 字段让设置页在没有技能时也能指路。
 */
@SpringBootTest(properties = {"pilot.store=memory", "pilot.runtime.provision=false", "pilot.web-search.local=false"})
@AutoConfigureMockMvc
class SkillsApiTest {
    private static final Path DATA =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-skills-api-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionService sessions;
    @TempDir
    Path workspace;

    @Test
    void listsSessionWorkspaceSkillsWithInstallRoots() throws Exception {
        writeSkill("demo-review", "审阅当前未提交的改动。", "/review");
        Path broken = Files.createDirectories(workspace.resolve(".pilot/skills").resolve("broken"));
        Files.writeString(broken.resolve("SKILL.md"), "---\nname: broken\n---\n没有 description 的技能");
        String session = sessions.createSession("技能测试", workspace.toString()).id();

        mvc.perform(get("/api/skills").param("sessionId", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.available.length()").value(1))
            .andExpect(jsonPath("$.available[0].name").value("demo-review"))
            .andExpect(jsonPath("$.available[0].command").value("/review"))
            .andExpect(jsonPath("$.available[0].source").value("project"))
            .andExpect(jsonPath("$.invalid.length()").value(1)).andExpect(jsonPath("$.invalid[0].name").value("broken"))
            .andExpect(jsonPath("$.projectRoot")
                .value(workspace.resolve(".pilot/skills").toAbsolutePath().normalize().toString()))
            .andExpect(jsonPath("$.userRoot").value(DATA.resolve("skills").toString()));

        mvc.perform(get("/api/skills").param("sessionId", "missing")).andExpect(status().isNotFound());
    }

    @Test
    void emptyCatalogStillReportsInstallRootsForGuidance() throws Exception {
        String session = sessions.createSession("空目录", workspace.toString()).id();
        mvc.perform(get("/api/skills").param("sessionId", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.available").isEmpty()).andExpect(jsonPath("$.invalid").isEmpty())
            .andExpect(jsonPath("$.projectRoot").isNotEmpty()).andExpect(jsonPath("$.userRoot").isNotEmpty());
    }

    private void writeSkill(String name, String description, String command) throws Exception {
        Path dir = Files.createDirectories(workspace.resolve(".pilot/skills").resolve(name));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + description
            + (command == null ? "" : "\ncommand: " + command) + "\n---\n正文内容");
    }
}
