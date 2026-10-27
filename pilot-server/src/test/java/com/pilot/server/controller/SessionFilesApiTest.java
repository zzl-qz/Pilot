package com.pilot.server.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class SessionFilesApiTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @TempDir
    Path workspace;

    @Test
    void listsDirectoriesBeforeFiles() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("README.md"), "# demo");
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/files", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(""))
                .andExpect(jsonPath("$.entries[0].name").value("src"))
                .andExpect(jsonPath("$.entries[0].directory").value(true))
                .andExpect(jsonPath("$.entries[1].name").value("README.md"))
                .andExpect(jsonPath("$.entries[1].directory").value(false));
    }

    @Test
    void returnsRelativePathsForNestedDirectories() throws Exception {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("src/main/App.java"), "class App {}");
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/files", sessionId).param("path", "src/main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/main"))
                .andExpect(jsonPath("$.parent").value("src"))
                .andExpect(jsonPath("$.entries[0].path").value("src/main/App.java"));
    }

    @Test
    void listsEveryFileRecursivelyForMentions() throws Exception {
        Files.createDirectories(workspace.resolve("pilot-core/src"));
        Files.createDirectories(workspace.resolve("node_modules/lib"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("pilot-core/src/App.java"), "class App {}");
        Files.writeString(workspace.resolve("node_modules/lib/index.js"), "export {};");
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/files/flat", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(2))
                .andExpect(jsonPath("$.files[0]").value("pilot-core/src/App.java"))
                .andExpect(jsonPath("$.files[1]").value("pom.xml"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void truncatesFlatListWhenLimitIsReached() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.txt"), "b");
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/files/flat", sessionId).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    @Test
    void previewsTextFileContent() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "第一行\n第二行\n");
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/file", sessionId).param("path", "a.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("a.txt"))
                .andExpect(jsonPath("$.binary").value(false))
                .andExpect(jsonPath("$.content").value("第一行\n第二行\n"));
    }

    @Test
    void rejectsPathOutsideWorkspace() throws Exception {
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/file", sessionId).param("path", "../outside.txt"))
                .andExpect(status().isBadRequest());
    }

    private String createSession() throws Exception {
        String body = json.writeValueAsString(Map.of("workspace", workspace.toString()));
        String response = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("sessionId").asString();
    }
}
