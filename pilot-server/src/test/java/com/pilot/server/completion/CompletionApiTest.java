package com.pilot.server.completion;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.validation.WorkspaceFingerprint;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 完成凭证/策略/胶囊的 HTTP 契约；同时验证 Spring 装配没有漏 bean。 */
@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
@com.pilot.server.WindowsJdkLoopbackSensitive
class CompletionApiTest {
    private static final Path DATA =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-completion-api-" + UUID.randomUUID());

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private TaskControlService tasks;

    @Autowired
    private ValidationLedger validations;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TaskCapsuleService capsules;

    @TempDir
    Path workspace;

    @Test
    void completionPolicyVerifyAndCapsuleEndpointsWork() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {}\n");
        Files.writeString(workspace.resolve("pilot.policy.yaml"), """
            checks:
              "src/**": "mvn -q test"
            """);
        String projectId = "prj_" + UUID.randomUUID();
        projects.create(projectId, "api-test", List.of(workspace.toString()), null, Instant.now());
        Session session = sessions.createSession("完成凭证接口", workspace.toString(), projectId, false);
        tasks.start(session.id(), "实现接口", List.of(), List.of());
        List<String> files = List.of("src/App.java");
        validations.record(
            new ValidationEvidence("val_api", session.id(), "run_api", WorkspaceFingerprint.of(workspace, files), files,
                List.of("mvn -q test → exit 0"), ValidationEvidence.PASSED, "test", "test", Instant.now()));

        mvc.perform(get("/api/sessions/{id}/completion", session.id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.goal").value("实现接口")).andExpect(jsonPath("$.policy.configured").value(true))
            .andExpect(jsonPath("$.policy.passed").value(true))
            .andExpect(jsonPath("$.verification.status").value("verified"));

        mvc.perform(get("/api/sessions/{id}/completion/markdown", session.id())).andExpect(status().isOk())
            .andExpect(content().string(containsString("完成凭证")))
            .andExpect(content().string(containsString("mvn -q test")));

        mvc.perform(post("/api/sessions/{id}/completion/verify", session.id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("verified")).andExpect(jsonPath("$.code").value("ok"));

        mvc.perform(get("/api/sessions/{id}/capsule", session.id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.completion.goal").value("实现接口"));

        ObjectMapper mapper = JsonMapper.builder().build();
        String capsule = mapper.writeValueAsString(capsules.export(session.id()));
        mvc.perform(
            post("/api/projects/{projectId}/capsules", projectId).contentType("application/json").content(capsule))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId", startsWith("ses_")))
            .andExpect(jsonPath("$.note", containsString("差异未自动应用")));
    }
}
