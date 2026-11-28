package com.pilot.server.memory;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pilot.core.memory.MemoryStore;
import com.pilot.core.memory.MemoryType;
import com.pilot.core.permission.PermissionMode;
import com.pilot.server.api.GlobalApiExceptionHandler;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.workspace.WorkspaceService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MemoryControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private Fixture fixture() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path data = Files.createDirectories(temp.resolve("data"));
        PilotDataDir dataDir = new PilotDataDir(data.toString());
        AgentProperties properties =
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 20, null, null, 30, 1, null, null, null);
        MemoryService service = new MemoryService(dataDir, new WorkspaceService(), properties);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(service))
            .setControllerAdvice(new GlobalApiExceptionHandler()).build();
        MemoryStore store = new MemoryStore(dataDir.file("memory"));
        return new Fixture(mvc, workspace, store);
    }

    @Test
    void approvesDraftsAndManagesEntriesViaHttpEndpoints() throws Exception {
        Fixture f = fixture();
        MemoryStore.Draft draft = f.store().addDraft(f.workspace(), MemoryType.FEEDBACK, "Use Spotless",
            "Run spotless before verify", "Style gate");

        f.mvc().perform(get("/api/memory").param("workspace", f.workspace().toString())).andExpect(status().isOk())
            .andExpect(jsonPath("$.drafts", hasSize(1))).andExpect(jsonPath("$.drafts[0].id").value(draft.id()))
            .andExpect(jsonPath("$.projectEntries", hasSize(0)));

        f.mvc()
            .perform(post("/api/memory/drafts/{id}/approve", draft.id()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("workspace", f.workspace().toString(), "scope", "project"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("use-spotless"))
            .andExpect(jsonPath("$.scope").value("project"));

        String createdBody = f.mvc()
            .perform(post("/api/memory/entries").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("workspace", f.workspace().toString(), "scope", "user", "type",
                    "user", "title", "Role", "note", "Backend Java developer", "why", "Adjust explanation depth"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("role")).andReturn().getResponse()
            .getContentAsString();
        JsonNode created = JSON.readTree(createdBody);
        assertEquals("user", created.path("scope").asString());

        f.mvc()
            .perform(put("/api/memory/entries/{slug}", "use-spotless").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("workspace", f.workspace().toString(), "scope", "project",
                    "type", "project", "title", "Use Spotless", "note", "Run mvnw spotless:apply", "why", "CI check"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("project"));

        f.mvc().perform(get("/api/memory").param("workspace", f.workspace().toString())).andExpect(status().isOk())
            .andExpect(jsonPath("$.drafts", hasSize(0))).andExpect(jsonPath("$.projectEntries", hasSize(1)))
            .andExpect(jsonPath("$.userEntries", hasSize(1)));

        f.mvc().perform(delete("/api/memory/entries/{slug}", "use-spotless")
            .param("workspace", f.workspace().toString()).param("scope", "project")).andExpect(status().isNoContent());

        assertFalse(Files.exists(f.workspace().resolve(".pilot/memory-draft.md")));
        assertTrue(f.store().listEntries(f.workspace(), MemoryStore.SCOPE_PROJECT).isEmpty());
    }

    @Test
    void discardsDraftAndMapsValidationAndMissingErrors() throws Exception {
        Fixture f = fixture();
        MemoryStore.Draft draft =
            f.store().addDraft(f.workspace(), MemoryType.PROJECT, "Drop me", "Temporary note", "");

        f.mvc().perform(delete("/api/memory/drafts/{id}", draft.id()).param("workspace", f.workspace().toString()))
            .andExpect(status().isNoContent());
        assertTrue(f.store().listDrafts(f.workspace()).isEmpty());

        f.mvc().perform(delete("/api/memory/drafts/{id}", "missing").param("workspace", f.workspace().toString()))
            .andExpect(status().isNotFound());

        f.mvc()
            .perform(post("/api/memory/entries").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("workspace", f.workspace().toString(), "scope", "invalid",
                    "type", "feedback", "note", "bad scope"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());

        f.mvc()
            .perform(delete("/api/memory/entries/{slug}", "BAD_SLUG").param("workspace", f.workspace().toString())
                .param("scope", "project"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());

        f.mvc().perform(delete("/api/memory/entries/{slug}", "non-existent")
            .param("workspace", f.workspace().toString()).param("scope", "project")).andExpect(status().isNotFound());
    }

    @Test
    void countsDraftsAndImportsLegacyWorkspaceFileViaHttp() throws Exception {
        Fixture f = fixture();
        f.mvc().perform(get("/api/memory/drafts/count").param("workspace", f.workspace().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        f.mvc().perform(get("/api/memory").param("workspace", f.workspace().toString())).andExpect(status().isOk())
            .andExpect(jsonPath("$.hasLegacyDrafts").value(false));

        Path legacy = Files.createDirectories(f.workspace().resolve(".pilot")).resolve("memory-draft.md");
        Files.writeString(legacy, "- [2026-11-01] [project] 运行 test 前先编译前端\n");

        f.mvc().perform(get("/api/memory").param("workspace", f.workspace().toString())).andExpect(status().isOk())
            .andExpect(jsonPath("$.hasLegacyDrafts").value(true));

        f.mvc()
            .perform(post("/api/memory/import-legacy").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("workspace", f.workspace().toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sourceFound").value(true))
            .andExpect(jsonPath("$.imported").value(1)).andExpect(jsonPath("$.skipped").value(0));

        f.mvc().perform(get("/api/memory/drafts/count").param("workspace", f.workspace().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
    }

    private record Fixture(MockMvc mvc, Path workspace, MemoryStore store) {
    }
}
