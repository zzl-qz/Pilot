package com.pilot.server.work;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.plan.PlanSink;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.agent.AgentRunService;

@SpringBootTest(properties = {"pilot.store=memory", "pilot.runtime.provision=false", "pilot.web-search.local=false"})
@AutoConfigureMockMvc
class WorkProductsApiTest {
    private static final Path DATA =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-work-api-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionService sessions;
    @Autowired
    WorkProductService products;
    @Autowired
    SkillDraftService drafts;
    @Autowired
    AgentRunService runs;
    @TempDir
    Path workspace;

    @Test
    void mainSessionHasDeliverableToolsButReviewDoesNot() {
        String session = sessions.createSession("工具绑定", workspace.toString()).id();
        PlanSink sink = (items, explanation) -> {
        };
        ToolRegistry main =
            ReflectionTestUtils.invokeMethod(runs, "sessionTools", session, "run", workspace.toString(), sink);
        ToolRegistry review = ReflectionTestUtils.invokeMethod(runs, "reviewTools", workspace.toString());
        assertTrue(main.names().containsAll(List.of("publish_artifact", "propose_skill")));
        assertFalse(review.names().contains("publish_artifact"));
        assertFalse(review.names().contains("propose_skill"));
    }

    @Test
    void downloadAndPreviewUseOwnedSnapshotAndReturnActionableFailures() throws Exception {
        String session = sessions.createSession("文件交付", workspace.toString()).id();
        Files.writeString(workspace.resolve("中文报告.md"), "# 内容\n已核对");
        var product = products.publish(session, "run", "中文报告.md", "中文报告", "摘要", List.of());
        String base = "/api/sessions/" + session + "/products";
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("中文报告"));
        mvc.perform(get(base + "/" + product.id() + "/preview")).andExpect(status().isOk())
            .andExpect(jsonPath("$.format").value("markdown"));
        mvc.perform(get(base + "/" + product.id() + "/download")).andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().bytes("# 内容\n已核对".getBytes(StandardCharsets.UTF_8)));
        String other = sessions.createSession("其他会话", workspace.toString()).id();
        mvc.perform(get("/api/sessions/" + other + "/products/" + product.id() + "/download"))
            .andExpect(status().isNotFound());
        Files.delete(DATA.resolve("work-products").resolve(product.id() + ".bin"));
        mvc.perform(get(base + "/" + product.id() + "/download")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void approvalRequiresVersionAndRefreshesCatalogAndRejectsConflictingRollback() throws Exception {
        String session = sessions.createSession("技能审核", workspace.toString()).id();
        var draft = drafts.propose(session, "run", "report-check", "报告核对", "步骤", "真实检查");
        String base = "/api/sessions/" + session + "/skill-drafts/" + draft.id();
        mvc.perform(post(base + "/approve").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(base + "/approve").contentType("application/json").content("{\"version\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/skills").param("sessionId", session))
            .andExpect(jsonPath("$.available[0].name").value("report-check"));
        mvc.perform(post(base + "/approve").contentType("application/json").content("{\"version\":1}"))
            .andExpect(status().isConflict());
        Files.writeString(workspace.resolve(".pilot/skills/report-check/SKILL.md"), "用户新版本");
        mvc.perform(post(base + "/revert").contentType("application/json").content("{\"version\":3}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty());
    }
}
